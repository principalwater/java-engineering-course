package dev.principalwater.bank.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class PostgresTestBase extends SecurityTestBase {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;

    // Один контейнер на JVM и стабильные свойства сохраняют кеш Spring TestContext.
    private static final Database DATABASE = database();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::url);
        properties.add("spring.datasource.username", DATABASE::username);
        properties.add("spring.datasource.password", DATABASE::password);
    }

    private static Database database() {
        String server = System.getenv("BANK_TEST_POSTGRES_SERVER");
        if (server == null) {
            var container =
                    new PostgreSQLContainer<>("postgres:17.11-alpine")
                            .withDatabaseName("bank_test");
            container.start();
            return new Database(
                    container.getJdbcUrl(), container.getUsername(), container.getPassword());
        }
        // Нативный CI использует одноразовый PostgreSQL без Docker. Ограничиваем адрес loopback
        // и создаём отдельную БД каждой JVM: миграции разных сервисов не пересекаются.
        if (!server.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalArgumentException(
                    "Native test PostgreSQL must be an explicit loopback server");
        }
        String username = System.getenv("BANK_TEST_POSTGRES_USER");
        String password = System.getenv("BANK_TEST_POSTGRES_PASSWORD");
        String name = "bank_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(server, username, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot create isolated native test database", failure);
        }
        return new Database(
                server.substring(0, server.lastIndexOf('/') + 1) + name, username, password);
    }

    private record Database(String url, String username, String password) {
        @Override
        public String toString() {
            return "Isolated test database";
        }
    }

    // Очищаются только данные конкретного сервиса; сам контейнер и Spring-контекст живут дальше.
    @BeforeEach
    protected final void resetPersistentState() {
        resetDatabase();
    }

    protected abstract void resetDatabase();
}
