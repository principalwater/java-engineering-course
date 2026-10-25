package dev.principalwater.bank.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import javax.net.ServerSocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;

// Настоящий logback-spring.xml, загруженный логированием Boot, и настоящий TCP/TLS-сокет.
class LogstashTransportTest {
    private static final Duration DELIVERY = Duration.ofSeconds(10);
    private static final String APPENDER = "BANK_LOGSTASH";
    private static final int QUEUE_SIZE = 256;
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path directory;
    private LoggingSystem logging;

    @AfterEach
    void stopLogging() {
        MDC.clear();
        context().stop();
        logging.cleanUp();
    }

    @Test
    void defaultTransportAttachesNoLogstashAppenderAndUnknownValueDoesNotEither() {
        start(Map.of());
        assertThat(root().getAppender(APPENDER)).isNull();
        assertThat(root().getAppender("CONSOLE")).isNotNull();
        logging.cleanUp();
        context().stop();

        start(Map.of("bank.logging.logstash.transport", "carrier-pigeon"));
        assertThat(root().getAppender(APPENDER)).isNull();
    }

    @Test
    void tcpDeliversTheConfiguredEncoderWithServiceAndTraceCorrelation() throws Exception {
        try (FakeLogstash logstash = new FakeLogstash(ServerSocketFactory.getDefault())) {
            start(tcp(logstash.port()));
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("spanId", "00f067aa0ba902b7");
            org.slf4j.Logger log = LoggerFactory.getLogger("fixture.payments");
            log.warn(
                    "Upstream rejected Authorization: Bearer abcdefghijklmnop",
                    new SQLException("secret"));

            JsonNode event = json.readTree(logstash.next(DELIVERY));
            assertThat(event.get("service").asText()).isEqualTo("fixture-service");
            assertThat(event.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(event.get("spanId").asText()).isEqualTo("00f067aa0ba902b7");
            assertThat(event.get("category").asText()).isEqualTo("SQLException");
            assertThat(event.toString()).doesNotContain("abcdefghijklmnop", "secret");
        }
    }

    @Test
    void tlsDeliversOnlyWhenTheExternalTrustStoreTrustsTheServer() throws Exception {
        TlsFixture server = new TlsFixture(directory, "server");
        TlsFixture stranger = new TlsFixture(directory, "stranger");
        try (FakeLogstash logstash = new FakeLogstash(server.serverSockets())) {
            start(tls(logstash.port(), stranger.trustStore()));
            LoggerFactory.getLogger("fixture.tls").info("untrusted attempt");
            assertThat(logstash.next(Duration.ofSeconds(3))).isNull();
            context().stop();
            logging.cleanUp();

            start(tls(logstash.port(), server.trustStore()));
            LoggerFactory.getLogger("fixture.tls").info("trusted delivery");
            assertThat(json.readTree(logstash.next(DELIVERY)).get("event").asText())
                    .isEqualTo("trusted delivery");
        }
    }

    @Test
    void trustedCertificateForAnotherHostCannotReceiveLogs() throws Exception {
        TlsFixture otherHost =
                new TlsFixture(directory, "other-host", "san=dns:another-service.invalid");
        try (FakeLogstash logstash = new FakeLogstash(otherHost.serverSockets())) {
            start(tls(logstash.port(), otherHost.trustStore()));
            LoggerFactory.getLogger("fixture.tls").info("must not reach the wrong identity");
            assertThat(logstash.next(Duration.ofSeconds(3))).isNull();
        }
    }

    @Test
    void unreachableLogstashNeverBlocksCallersAndStopsWithinTheGracePeriod() {
        start(tcp(1));
        org.slf4j.Logger log = LoggerFactory.getLogger("fixture.burst");
        // Втрое больше очереди: блокирующее поведение зависло бы на заполненном буфере.
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    for (int index = 0; index < QUEUE_SIZE * 3; index++) {
                        log.info("burst {}", index);
                    }
                });
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> context().stop());
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(Thread.getAllStackTraces().keySet())
                                        .noneMatch(
                                                thread ->
                                                        thread.getName()
                                                                .contains(
                                                                        "logback-appender-"
                                                                                + APPENDER)));
        assertThatNoException().isThrownBy(() -> log.info("after stop"));
    }

    private Map<String, Object> tcp(int port) {
        return Map.of(
                "bank.logging.logstash.transport",
                "tcp",
                "bank.logging.logstash.destination",
                "127.0.0.1:" + port,
                "bank.logging.logstash.queue-size",
                QUEUE_SIZE);
    }

    private Map<String, Object> tls(int port, Path trustStore) {
        return Map.of(
                "bank.logging.logstash.transport",
                "tls",
                "bank.logging.logstash.destination",
                "127.0.0.1:" + port,
                "bank.logging.logstash.queue-size",
                QUEUE_SIZE,
                "bank.logging.logstash.trust-store",
                trustStore.toUri().toString(),
                "bank.logging.logstash.trust-store-password",
                TlsFixture.PASSWORD);
    }

    private void start(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "fixture-service");
        properties.forEach((key, value) -> environment.setProperty(key, String.valueOf(value)));
        logging = LoggingSystem.get(getClass().getClassLoader());
        logging.beforeInitialize();
        logging.initialize(new LoggingInitializationContext(environment), null, null);
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static Logger root() {
        return context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
