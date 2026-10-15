package dev.principalwater.store;

import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
public abstract class DatabaseTestBase extends ReactiveTestBase {
    @Autowired protected DatabaseClient database;
    @Autowired protected ApplicationContext context;

    @BeforeEach
    @AfterEach
    protected final void cleanDatabase() {
        String url = context.getEnvironment().getRequiredProperty("spring.r2dbc.url");
        assertTrue(Set.of("store-tests7", "store_tests")
                        .contains(ConnectionFactoryOptions.parse(url).getRequiredValue(DATABASE)),
                "Database cleanup requires its isolated test database");
        // R2DBC не участвует в test-managed JDBC rollback: очищаем отдельную БД в порядке FK.
        Flux.just("checkout_intent_lines", "checkout_intents", "analytics_outbox", "order_items", "purchase_orders", "cart_items", "store_users",
                        "shopping_carts", "products")
                .concatMap(table -> database.sql("DELETE FROM " + table).fetch().rowsUpdated())
                .then().block(TIMEOUT);
    }

    protected final UUID createAccountCart() {
        UUID cartId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // Service fixtures не исполняют login: реальный хеш и rotation проверяет AccountIntegrationTest.
        database.sql("INSERT INTO shopping_carts(id, version) VALUES (:id, 0)").bind("id", cartId).fetch().rowsUpdated()
                .then(database.sql("""
                        INSERT INTO store_users(id, cart_id, username, password_hash, display_name, role, version)
                        VALUES (:id, :cart, :username, 'service-fixture-unused-hash', 'Fixture', 'BUYER', 0)
                        """).bind("id", userId).bind("cart", cartId).bind("username", "fixture-" + userId.toString().substring(0, 24))
                        .fetch().rowsUpdated()).block(TIMEOUT);
        return cartId;
    }

    protected final UUID accountId(UUID cartId) {
        return database.sql("SELECT id FROM store_users WHERE cart_id = :cart").bind("cart", cartId)
                .map(row -> row.get("id", UUID.class)).one().block(TIMEOUT);
    }

    protected final void execute(String sql) {
        database.sql(sql).fetch().rowsUpdated().then().block(TIMEOUT);
    }
}
