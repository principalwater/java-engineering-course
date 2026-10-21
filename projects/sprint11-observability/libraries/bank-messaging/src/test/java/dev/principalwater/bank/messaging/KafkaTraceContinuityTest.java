package dev.principalwater.bank.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.BraveAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

// Настоящий брокер в JVM и настоящие KafkaTemplate/listener-контейнер из
// KafkaMessagingConfiguration:
// трасса отправителя должна дойти до обработчика через заголовки записи.
@EmbeddedKafka(partitions = 1, topics = KafkaTraceContinuityTest.TOPIC)
class KafkaTraceContinuityTest {
    static final String TOPIC = "observation.fixture";
    private static final List<MutableSpan> FINISHED = new CopyOnWriteArrayList<>();

    @Test
    void sentRecordCarriesTheTraceAndTheListenerContinuesItAsAChildOfTheSendSpan(
            EmbeddedKafkaBroker broker) throws Exception {
        FINISHED.clear();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(
                        AutoConfigurations.of(
                                MetricsAutoConfiguration.class,
                                CompositeMeterRegistryAutoConfiguration.class,
                                SimpleMetricsExportAutoConfiguration.class,
                                ObservationAutoConfiguration.class,
                                MicrometerTracingAutoConfiguration.class,
                                BraveAutoConfiguration.class,
                                JacksonAutoConfiguration.class,
                                ValidationAutoConfiguration.class))
                .withUserConfiguration(Fixture.class)
                .withPropertyValues(
                        "spring.config.import=classpath:bank-kafka.properties",
                        "spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.security.protocol=PLAINTEXT",
                        "bank.kafka.user=fixture-service",
                        "bank.kafka.password=fixture-password",
                        "bank.delivery.enabled=false",
                        "spring.kafka.listener.auto-startup=true",
                        "management.tracing.sampling.probability=1.0")
                .run(
                        context -> {
                            String clusterId = context.getBean(KafkaAdmin.class).clusterId();
                            assertThat(clusterId).isNotBlank();
                            Tracer tracer = context.getBean(Tracer.class);
                            Span request = tracer.nextSpan().name("request").start();
                            Tracer.SpanInScope scope = tracer.withSpan(request);
                            try {
                                context.getBean("bankKafkaTemplate", KafkaTemplate.class)
                                        .send(TOPIC, "key", "payload")
                                        .get(10, TimeUnit.SECONDS);
                            } finally {
                                scope.close();
                            }
                            request.end();

                            assertThat(
                                            context.getBean(Probe.class)
                                                    .traceId
                                                    .get(20, TimeUnit.SECONDS))
                                    .isEqualTo(request.context().traceId());
                            // Listener завершает future до остановки observation контейнером.
                            // Ожидаем сам финализированный span, а не случайный порядок потоков.
                            await().atMost(5, TimeUnit.SECONDS)
                                    .untilAsserted(
                                            () -> {
                                                MutableSpan send = span(brave.Span.Kind.PRODUCER);
                                                MutableSpan receive =
                                                        span(brave.Span.Kind.CONSUMER);
                                                assertThat(send.parentId())
                                                        .isEqualTo(request.context().spanId());
                                                assertThat(receive.traceId())
                                                        .isEqualTo(send.traceId());
                                                assertThat(receive.parentId()).isEqualTo(send.id());
                                                assertThat(send.remoteServiceName())
                                                        .endsWith(clusterId);
                                                assertThat(receive.remoteServiceName())
                                                        .endsWith(clusterId);
                                            });
                            // Собственные фабрики Kafka подключены к реестру метрик клиентов.
                            assertThat(context.getBean(MeterRegistry.class).getMeters())
                                    .extracting(meter -> meter.getId().getName())
                                    .anyMatch(name -> name.startsWith("kafka.producer."))
                                    .anyMatch(name -> name.startsWith("kafka.consumer."));
                        });
    }

    private static MutableSpan span(brave.Span.Kind kind) {
        List<MutableSpan> matches = FINISHED.stream().filter(span -> span.kind() == kind).toList();
        assertThat(matches).as("finished %s spans", kind).hasSize(1);
        return matches.getFirst();
    }

    static final class Probe {
        private final Tracer tracer;
        final CompletableFuture<String> traceId = new CompletableFuture<>();

        Probe(Tracer tracer) {
            this.tracer = tracer;
        }

        @KafkaListener(topics = TOPIC, groupId = "observation-fixture")
        void receive(String payload) {
            Span current = tracer.currentSpan();
            traceId.complete(current == null ? "none" : current.context().traceId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KafkaMessagingConfiguration.class)
    static class Fixture {
        @Bean
        JdbcTemplate jdbc() {
            // Outbox в этом тесте не используется: проверяются только фабрики Kafka.
            return mock(JdbcTemplate.class);
        }

        @Bean
        Probe probe(Tracer tracer) {
            return new Probe(tracer);
        }

        @Bean
        SpanHandler finishedSpans() {
            return new SpanHandler() {
                @Override
                public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                    FINISHED.add(span);
                    return true;
                }
            };
        }
    }
}
