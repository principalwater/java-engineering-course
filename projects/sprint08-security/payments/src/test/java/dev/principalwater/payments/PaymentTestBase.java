package dev.principalwater.payments;

import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Timeout(30)
public abstract class PaymentTestBase {
    protected static final Duration TIMEOUT = Duration.ofSeconds(15);
    @Autowired protected WebTestClient web;
    @Autowired protected DatabaseClient database;
    @Autowired private Environment environment;

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        PaymentTokens.registerIssuer(registry);
    }

    /** Access token витрины со scope обеих операций; негативные сценарии собирают токен через {@link PaymentTokens}. */
    protected static String accessToken() {
        return PaymentTokens.token(PaymentTokens.READ, PaymentTokens.WRITE);
    }

    @BeforeEach
    @AfterEach
    protected final void cleanDatabase() {
        String url = environment.getRequiredProperty("spring.r2dbc.url");
        assertTrue(Set.of("payments-tests8", "payments_tests")
                        .contains(ConnectionFactoryOptions.parse(url).getRequiredValue(DATABASE)),
                "Cleanup requires an isolated payments test database");
        Flux.just("payment_journal", "payment_decisions", "payment_wallets")
                .concatMap(table -> database.sql("DELETE FROM " + table).fetch().rowsUpdated()).then().block(TIMEOUT);
    }

    protected static <T> T await(Mono<T> operation) {
        return Objects.requireNonNull(operation.block(TIMEOUT), "Expected a reactive result");
    }

    protected final void execute(String sql) {
        database.sql(sql).fetch().rowsUpdated().then().block(TIMEOUT);
    }

    protected final long count(String table) {
        return await(database.sql("SELECT COUNT(*) AS total FROM " + table)
                .map(row -> row.get("total", Long.class)).one());
    }
}
