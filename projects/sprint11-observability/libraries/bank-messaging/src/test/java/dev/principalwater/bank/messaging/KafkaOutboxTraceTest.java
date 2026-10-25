package dev.principalwater.bank.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.messaging.replay.OutboxReplay;
import dev.principalwater.bank.observability.DeferredTrace;
import dev.principalwater.bank.testing.PostgresTestBase;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Реальный PostgreSQL и миграции db/kafka: родитель трассы сохраняется вместе с фактом и
// восстанавливается другим потоком позже; идентичность, аренда и ACK остаются прежними.
@SpringBootTest(
        classes = KafkaOutboxTraceTest.DatabaseApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=outbox_trace_test",
            "spring.config.import=classpath:bank-common.properties",
            "spring.flyway.locations=classpath:db/kafka",
            "management.tracing.sampling.probability=1.0"
        })
@AutoConfigureObservability
class KafkaOutboxTraceTest extends PostgresTestBase {
    private static final String TOPIC = "accounts.notifications";
    @Autowired private Tracer tracer;
    @Autowired private DeferredTrace trace;
    @Autowired private PlatformTransactionManager transactions;
    private final ExecutorService relayThread = Executors.newSingleThreadExecutor();
    private final AtomicReference<TraceContext> publishing = new AtomicReference<>();
    private KafkaTemplate<String, String> kafka;
    private KafkaOutbox outbox;

    @Override
    protected void resetDatabase() {
        jdbc.execute("TRUNCATE bank_kafka_outbox");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void reset() {
        publishing.set(null);
        kafka = mock(KafkaTemplate.class);
        // Контекст, видимый во время отправки, — это и есть то, что получила бы запись в Kafka.
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenAnswer(
                        call -> {
                            Span current = tracer.currentSpan();
                            publishing.set(current == null ? null : current.context());
                            return CompletableFuture.completedFuture(mock(SendResult.class));
                        });
        outbox = new KafkaOutbox(jdbc, mapper, kafka, trace);
    }

    @AfterEach
    void stop() {
        relayThread.shutdownNow();
    }

    @Test
    void delayedRelayOnAnotherThreadContinuesTheTraceOfTheOriginalRequest() throws Exception {
        Span request = tracer.nextSpan().name("request").start();
        Tracer.SpanInScope scope = tracer.withSpan(request);
        try {
            outbox.enqueue(TOPIC, "owner", "event-1", Map.of("amount", 100));
        } finally {
            scope.close();
        }
        request.end();
        assertThat(jdbc.queryForObject("SELECT traceparent FROM bank_kafka_outbox", String.class))
                .contains(request.context().traceId(), request.context().spanId());

        relay();

        assertThat(publishing.get().traceId()).isEqualTo(request.context().traceId());
        assertThat(publishing.get().parentId()).isEqualTo(request.context().spanId());
        assertThat(status()).isEqualTo("PUBLISHED");
    }

    @Test
    void repeatedIdentityKeepsTheFirstRecordAndItsOriginalTraceParent() {
        Span first = tracer.nextSpan().name("first").start();
        Tracer.SpanInScope firstScope = tracer.withSpan(first);
        try {
            outbox.enqueue(TOPIC, "owner", "event-1", Map.of("amount", 100));
        } finally {
            firstScope.close();
        }
        first.end();
        Span retry = tracer.nextSpan().name("retry").start();
        Tracer.SpanInScope retryScope = tracer.withSpan(retry);
        try {
            outbox.enqueue(TOPIC, "owner", "event-1", Map.of("amount", 100));
        } finally {
            retryScope.close();
        }
        retry.end();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_kafka_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT traceparent FROM bank_kafka_outbox", String.class))
                .contains(first.context().traceId())
                .doesNotContain(retry.context().traceId());
    }

    @Test
    void operatorReplayRetainsFactButStartsANewTrace() throws Exception {
        Span original = tracer.nextSpan().name("original-request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(original)) {
            outbox.enqueue(
                    BankTopics.CASH_NOTIFICATIONS, "owner", "manual-replay", Map.of("amount", 100));
        } finally {
            original.end();
        }
        relay();
        assertThat(publishing.get().traceId()).isEqualTo(original.context().traceId());
        var replay =
                new OutboxReplay(jdbc, new TransactionTemplate(transactions)).requeue("cash", 0, 1);
        assertThat(replay.requeued()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT traceparent FROM bank_kafka_outbox", String.class))
                .isNull();
        relay();
        assertThat(publishing.get().traceId()).isNotEqualTo(original.context().traceId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'amount' FROM bank_kafka_outbox", String.class))
                .isEqualTo("100");
    }

    @Test
    void recordWithoutStoredTraceIsStillPublishedAndFailedSendKeepsTheRetryContract()
            throws Exception {
        jdbc.update(
                "INSERT INTO bank_kafka_outbox(id,topic,message_key,message_id,payload) VALUES"
                        + " (?,?,?,?,?::jsonb)",
                UUID.randomUUID(),
                TOPIC,
                "owner",
                "legacy-1",
                "{\"amount\":100}");
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")))
                .thenAnswer(
                        call -> {
                            publishing.set(tracer.currentSpan().context());
                            return CompletableFuture.completedFuture(mock(SendResult.class));
                        });

        relay();

        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM bank_kafka_outbox", Integer.class))
                .isEqualTo(1);
        jdbc.update("UPDATE bank_kafka_outbox SET next_attempt_at = now() - interval '1 second'");
        relay();
        assertThat(status()).isEqualTo("PUBLISHED");
        assertThat(publishing.get()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"accounts", "notifications"})
    void retainedFailureFactsCanBeReplayedByTheirOwningApplication(String application)
            throws Exception {
        outbox.enqueue(
                BankTopics.BUSINESS_FAILURES, "owner", "failure-1", Map.of("type", "WITHDRAWAL"));
        relay();
        assertThat(status()).isEqualTo("PUBLISHED");
        var page =
                new OutboxReplay(jdbc, new TransactionTemplate(transactions))
                        .requeue(application, 0, 1000);
        assertThat(page.requeued()).isEqualTo(1);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'type' FROM bank_kafka_outbox", String.class))
                .isEqualTo("WITHDRAWAL");
    }

    private void relay() throws Exception {
        CompletableFuture.runAsync(() -> outbox.pendingIds().forEach(outbox::recover), relayThread)
                .get(15, TimeUnit.SECONDS);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM bank_kafka_outbox", String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class DatabaseApp {}
}
