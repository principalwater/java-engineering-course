package dev.principalwater.study.data;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {DataJdbcApplication.class, MySqlIntegrationTest.DatabaseConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MySqlIntegrationTest {
    @Autowired
    private AccountRepository accounts;

    /** Проверяется реальный MySQL: generated key, отображение записи, DECIMAL и удаление. */
    @Test
    void crudPreservesGeneratedIdAndDecimalBalance() {
        Account saved = accounts.save(new Account("O'Коннор"));
        assertThat(saved.id()).isPositive();
        try {
            assertThat(accounts.findById(saved.id())).contains(saved);
            Account updated = accounts.save(new Account(saved.id(), saved.name(), new BigDecimal("123.45")));
            assertThat(accounts.findById(saved.id())).contains(updated);
            assertThat(updated.balance()).isEqualByComparingTo("123.45");
        } finally {
            accounts.deleteById(saved.id());
        }
        assertThat(accounts.existsById(saved.id())).isFalse();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        MySQLContainer<?> database() {
            // Контейнером управляет Spring-контекст, поэтому его срок жизни совпадает с кешем контекста.
            return new MySQLContainer<>("mysql@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242");
        }
    }
}
