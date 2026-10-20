package dev.principalwater.bank.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:17.11-alpine").withDatabaseName("bank_test");

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    // Очищаются только данные конкретного сервиса; сам контейнер и Spring-контекст живут дальше.
    @BeforeEach
    protected final void resetPersistentState() {
        resetDatabase();
    }

    protected abstract void resetDatabase();
}
