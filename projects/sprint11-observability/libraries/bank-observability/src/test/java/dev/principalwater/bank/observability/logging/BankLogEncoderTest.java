package dev.principalwater.bank.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;
import net.logstash.logback.argument.StructuredArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BankLogEncoderTest {
    private static final String JWT =
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLWJ5dGVz";
    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("fixture.payments");
    private final ObjectMapper json = new ObjectMapper();
    private BankLogEncoder encoder;

    @BeforeEach
    void start() {
        context.setMDCAdapter(new LogbackMDCAdapter());
        encoder = new BankLogEncoder();
        encoder.setContext(context);
        encoder.setService("fixture-service");
        encoder.start();
    }

    @AfterEach
    void stop() {
        encoder.stop();
    }

    @Test
    void emitsOnlyTheAgreedFieldsAndNeverExceptionTextArgumentsOrForeignMdc() throws IOException {
        LoggingEvent event =
                new LoggingEvent(
                        Logger.class.getName(),
                        logger,
                        Level.WARN,
                        "Payment rejected",
                        new IllegalStateException(
                                "wrapper with 4276 3800 0000 0000",
                                new SQLException("INSERT ... VALUES ('DE89 3704') failed")),
                        new Object[] {
                            StructuredArguments.keyValue("iban", "DE89370400440532013000")
                        });
        event.setMDCPropertyMap(
                Map.of(
                        "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                        "spanId", "00f067aa0ba902b7",
                        "userToken", "must-not-leave-the-process"));

        JsonNode encoded = encode(event);

        assertThat(encoded.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "@timestamp",
                        "service",
                        "level",
                        "logger_name",
                        "event",
                        "traceId",
                        "spanId",
                        "category");
        assertThat(encoded.get("service").asText()).isEqualTo("fixture-service");
        assertThat(encoded.get("level").asText()).isEqualTo("WARN");
        assertThat(encoded.get("event").asText()).isEqualTo("Payment rejected");
        assertThat(encoded.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // Категория — класс первопричины, а не текст ни одного из исключений цепочки.
        assertThat(encoded.get("category").asText()).isEqualTo("SQLException");
        assertThat(encoded.toString())
                .doesNotContain("4276", "DE89", "VALUES", "must-not-leave-the-process");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secretShapedMessages")
    void secretShapedValuesAreReplacedBeforeTheyLeaveTheProcess(
            String shape, String message, String leaked) throws IOException {
        JsonNode encoded =
                encode(
                        new LoggingEvent(
                                Logger.class.getName(), logger, Level.INFO, message, null, null));

        assertThat(encoded.get("event").asText()).contains("[redacted]").doesNotContain(leaked);
    }

    @Test
    void overlongMessagesAreTruncatedAfterRedaction() throws IOException {
        String message = "x".repeat(5000);

        JsonNode encoded =
                encode(
                        new LoggingEvent(
                                Logger.class.getName(), logger, Level.INFO, message, null, null));

        assertThat(encoded.get("event").asText())
                .hasSize(1024 + "[truncated]".length())
                .endsWith("[truncated]");
    }

    static Stream<Arguments> secretShapedMessages() {
        return Stream.of(
                Arguments.of("jwt", "rejected token " + JWT, "eyJzdWIi"),
                Arguments.of(
                        "bearer", "Authorization: Bearer abcdefghijklmnop", "abcdefghijklmnop"),
                Arguments.of(
                        "basic", "Authorization: Basic dXNlcjpwYXNzd29yZA==", "dXNlcjpwYXNzd29yZA"),
                Arguments.of(
                        "session cookie",
                        "Cookie: __Host-bank-session=abc123def456",
                        "abc123def456"),
                Arguments.of(
                        "forwarded identity",
                        "X-Owner-Id: 3f2c1d9e-0000-4000-8000-000000000000",
                        "3f2c1d9e"),
                Arguments.of(
                        "form secret",
                        "client_secret=s3cr3t-value&grant_type=client_credentials",
                        "s3cr3t-value"),
                Arguments.of("password", "login failed password=hunter2hunter2", "hunter2hunter2"),
                Arguments.of(
                        "private PEM",
                        "bad material -----BEGIN PRIVATE KEY-----\nsecretKeyMaterial\n-----END PRIVATE KEY-----",
                        "secretKeyMaterial"));
    }

    private JsonNode encode(LoggingEvent event) throws IOException {
        return json.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));
    }
}
