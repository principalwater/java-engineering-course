package dev.principalwater.bank.analytics.config;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.principalwater.bank.analytics.AnalyticsTestBase;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.service.AnalyticsPublisher;
import dev.principalwater.bank.contract.BankEvent;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

@TestPropertySource(properties = "spring.kafka.security.protocol=PLAINTEXT")
class KafkaAnalyticsIntegrationTest extends AnalyticsTestBase {
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final TopicPartition PARTITION =
            new TopicPartition(AnalyticsProjection.TOPIC, 0);
    private final Admin admin = Fixture.admin();
    @Autowired private AnalyticsInbox inbox;
    @Autowired private AnalyticsPublisher publisher;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", Fixture.BROKER::getBootstrapServers);
    }

    @Override
    protected void resetDatabase() {
        stopConsumers();
        try {
            // Удаляем только записи своего test-container topic, сохраняя тот же broker и Spring
            // context.
            admin.deleteRecords(Map.of(PARTITION, RecordsToDelete.beforeOffset(endOffset())))
                    .all()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot reset Kafka fixture", failure);
        }
        super.resetDatabase();
    }

    @AfterEach
    void closeScenarioClients() {
        stopConsumers();
        admin.close(Duration.ofSeconds(5));
    }

    @Test
    void relayDrainsTwoHundredDurableEventsWithinFiniteDeadline() {
        for (int number = 0; number < 200; number++) {
            inbox.acceptFinancial(event());
        }
        startConsumers();
        long started = System.nanoTime();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            await().atMost(WAIT)
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            200,
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM analytics_inbox WHERE cassandra_done AND warehouse_done",
                                                    Integer.class)));
            awaitCommitted(endOffset());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(elapsedMillis < WAIT.toMillis());
        System.out.println("Relay drained 200 durable events in " + elapsedMillis + "ms");
    }

    @Test
    void relayRepublishesDurablePendingFactAfterKafkaRecordsAreDeleted() throws Exception {
        BankEvent event = event();
        inbox.acceptFinancial(event);
        long firstOffset = endOffset();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            await().atMost(WAIT)
                    .untilAsserted(
                            () -> {
                                assertTrue(endOffset() > firstOffset);
                                assertTrue(
                                        jdbc.queryForObject(
                                                "SELECT attempts > 0 AND lease_token IS NULL FROM analytics_inbox WHERE event_id = ?",
                                                Boolean.class,
                                                event.eventId()));
                            });
        }
        assertFalse(done(event, true));
        assertFalse(done(event, false));
        long removedUntil = endOffset();
        long lowWatermark =
                admin.deleteRecords(Map.of(PARTITION, RecordsToDelete.beforeOffset(removedUntil)))
                        .lowWatermarks()
                        .get(PARTITION)
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .lowWatermark();
        assertTrue(lowWatermark >= removedUntil);
        // Сдвигаем только retry-clock строки fixture: неизменный payload/ID обязан пережить потерю
        // записи Kafka.
        jdbc.update(
                "UPDATE analytics_inbox SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                event.eventId());
        startConsumers();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            awaitAcknowledged(event);
            awaitCommitted(endOffset());
        }
        verify(cassandra, times(1)).insert(event);
        verify(warehouse, times(1)).insert(event);
        assertEquals(
                event,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox WHERE event_id = ?",
                                String.class,
                                event.eventId()),
                        BankEvent.class));
    }

    @Test
    void logicalRedeliveryAndConsumerRestartSkipAlreadyAcknowledgedSinks() {
        BankEvent event = event();
        inbox.acceptFinancial(event);
        startConsumers();
        publisher.publish(event);
        awaitAcknowledged(event);
        awaitCommitted(endOffset());
        stopConsumers();
        startConsumers();
        publisher.publish(event);
        awaitCommitted(endOffset());
        verify(cassandra, times(1)).insert(event);
        verify(warehouse, times(1)).insert(event);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analytics_inbox", Integer.class));
    }

    @Test
    void sinkWriteBeforeFailedPgAcknowledgementReplaysSameOffsetAfterConsumerRestart() {
        BankEvent event = event();
        inbox.acceptFinancial(event);
        long offset = endOffset();
        jdbc.execute(
                "ALTER TABLE analytics_inbox ADD CONSTRAINT block_personal_ack CHECK (NOT cassandra_done)");
        try {
            startConsumers();
            publisher.publish(event);
            await().atMost(WAIT)
                    .untilAsserted(
                            () -> {
                                verify(cassandra, atLeast(1)).insert(event);
                                assertTrue(done(event, false));
                            });
            assertFalse(done(event, true));
            assertTrue(committed(AnalyticsProjection.PERSONAL) <= offset);
            stopConsumers();
        } finally {
            jdbc.execute("ALTER TABLE analytics_inbox DROP CONSTRAINT block_personal_ack");
        }
        startConsumers();
        awaitAcknowledged(event);
        awaitCommitted(offset + 1);
        verify(cassandra, atLeast(2)).insert(event);
        verify(warehouse, times(1)).insert(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "key", "payload", "unknown", "null", "oversize"})
    void poisonIsDurablyQuarantinedAndDoesNotBlockTheNextValidFact(String kind) throws Exception {
        BankEvent event = event();
        inbox.acceptFinancial(event);
        String value = mapper.writeValueAsString(event);
        String key = event.ownerId().toString();
        String reason;
        switch (kind) {
            case "malformed" -> {
                value = "{";
                reason = "MALFORMED";
            }
            case "key" -> {
                key = BOB.toString();
                reason = "KEY_MISMATCH";
            }
            case "payload" -> {
                value =
                        mapper.writeValueAsString(
                                new BankEvent(event.eventId(), ALICE, "DEPOSIT", 30, 100, NOW));
                reason = "PAYLOAD_MISMATCH";
            }
            case "unknown" -> {
                value = mapper.writeValueAsString(event());
                reason = "UNKNOWN_EVENT";
            }
            case "null" -> {
                value = null;
                reason = "MALFORMED";
            }
            case "oversize" -> {
                value =
                        mapper.writeValueAsString(
                                new BankEvent(
                                        event.eventId(), ALICE, "A".repeat(5000), 29, 100, NOW));
                reason = "MALFORMED";
            }
            default -> throw new IllegalArgumentException("Unknown Kafka fixture");
        }
        startConsumers();
        RecordMetadata bad =
                kafka.send(AnalyticsProjection.TOPIC, key, value)
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .getRecordMetadata();
        await().atMost(WAIT)
                .untilAsserted(() -> assertEquals(2, quarantineCount(bad.offset(), reason)));
        awaitCommitted(bad.offset() + 1);
        verifyNoInteractions(cassandra, warehouse);
        publisher.publish(event);
        awaitAcknowledged(event);
        awaitCommitted(bad.offset() + 2);
    }

    @Test
    void poisonOffsetWaitsForDurableQuarantineWhenDatabaseIsBlocked() throws Exception {
        long offset = endOffset();
        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE analytics_quarantine IN ACCESS EXCLUSIVE MODE");
            }
            startConsumers();
            kafka.send(AnalyticsProjection.TOPIC, ALICE.toString(), "{")
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            // pg_stat_activity доказывает реальное ожидание обоих INSERT, а не предполагаемую
            // задержку потока.
            await().atMost(WAIT)
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            2,
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                                                            + "AND wait_event_type='Lock' AND query LIKE 'INSERT INTO analytics_quarantine%'",
                                                    Integer.class)));
            for (AnalyticsProjection projection : AnalyticsProjection.values()) {
                assertTrue(committed(projection) <= offset);
            }
            blocker.rollback();
        }
        await().atMost(WAIT)
                .untilAsserted(() -> assertEquals(2, quarantineCount(offset, "MALFORMED")));
        awaitCommitted(offset + 1);
        verifyNoInteractions(cassandra, warehouse);
    }

    private BankEvent event() {
        return new BankEvent(UUID.randomUUID(), ALICE, "DEPOSIT", 29, 100, NOW);
    }

    private int quarantineCount(long offset, String reason) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM analytics_quarantine WHERE record_offset = ? AND reason = ?",
                Integer.class,
                offset,
                reason);
    }

    private boolean done(BankEvent event, boolean personal) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT CASE WHEN ? THEN cassandra_done ELSE warehouse_done END "
                                + "FROM analytics_inbox WHERE event_id = ?",
                        Boolean.class,
                        personal,
                        event.eventId()));
    }

    private void awaitAcknowledged(BankEvent event) {
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            assertTrue(done(event, true));
                            assertTrue(done(event, false));
                        });
    }

    private void startConsumers() {
        listeners.getListenerContainers().forEach(container -> container.start());
    }

    private void stopConsumers() {
        listeners.getListenerContainers().forEach(container -> container.stop());
    }

    private long endOffset() {
        try {
            ListOffsetsResult.ListOffsetsResultInfo result =
                    admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest()))
                            .partitionResult(PARTITION)
                            .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return result.offset();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Kafka end offset", failure);
        }
    }

    private long committed(AnalyticsProjection projection) {
        try {
            OffsetAndMetadata offset =
                    admin.listConsumerGroupOffsets(projection.getGroupId())
                            .partitionsToOffsetAndMetadata()
                            .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                            .get(PARTITION);
            return offset == null ? -1 : offset.offset();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Kafka committed offset", failure);
        }
    }

    private void awaitCommitted(long nextOffset) {
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            for (AnalyticsProjection projection : AnalyticsProjection.values()) {
                                assertTrue(committed(projection) >= nextOffset);
                            }
                        });
    }

    private static final class Fixture {
        private static final Duration ADMIN_REQUEST_TIMEOUT = Duration.ofSeconds(5);
        private static final Duration ADMIN_API_TIMEOUT = Duration.ofSeconds(10);
        private static final KafkaContainer BROKER =
                new KafkaContainer("apache/kafka:4.2.2")
                        .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                        .withCreateContainerCmdModifier(
                                command ->
                                        command.getHostConfig()
                                                .withMemory(768L * 1024 * 1024)
                                                .withNanoCPUs(1_000_000_000L));

        static {
            BROKER.start();
            try (Admin admin = admin()) {
                admin.createTopics(List.of(new NewTopic(AnalyticsProjection.TOPIC, 1, (short) 1)))
                        .all()
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot initialize Kafka fixture", failure);
            }
        }

        private static Admin admin() {
            return Admin.create(
                    Map.of(
                            "bootstrap.servers",
                            BROKER.getBootstrapServers(),
                            "request.timeout.ms",
                            Math.toIntExact(ADMIN_REQUEST_TIMEOUT.toMillis()),
                            "default.api.timeout.ms",
                            Math.toIntExact(ADMIN_API_TIMEOUT.toMillis())));
        }
    }
}
