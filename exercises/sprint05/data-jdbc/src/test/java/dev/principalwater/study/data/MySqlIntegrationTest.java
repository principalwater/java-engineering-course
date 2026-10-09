package dev.principalwater.study.data;

import java.math.BigDecimal;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {DataJdbcApplication.class, MySqlIntegrationTest.DatabaseConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MySqlIntegrationTest {
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private TransferService transfers;

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

    /** Перевод либо изменяет обе записи, либо сохраняет обе после нарушения CHECK у отправителя. */
    @Test
    void transferCommitsBothChangesAndRollsBackOnOverdraft() {
        Account source = accounts.save(new Account("Отправитель"));
        Account target = accounts.save(new Account("Получатель"));
        try {
            transfers.transfer(source.id(), target.id(), new BigDecimal("500.00"));
            Account committedSource = accounts.findById(source.id()).orElseThrow();
            Account committedTarget = accounts.findById(target.id()).orElseThrow();
            assertThat(committedSource.balance()).isEqualByComparingTo("9500.00");
            assertThat(committedTarget.balance()).isEqualByComparingTo("10500.00");
            assertThatThrownBy(() -> transfers.transfer(source.id(), target.id(), new BigDecimal("100000.00")))
                    .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(SQLException.class);
            assertThat(accounts.findById(source.id())).contains(committedSource);
            assertThat(accounts.findById(target.id())).contains(committedTarget);
            assertThatThrownBy(() -> transfers.transfer(source.id(), target.id(), new BigDecimal("0.001")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(accounts.findById(source.id())).contains(committedSource);
            assertThat(accounts.findById(target.id())).contains(committedTarget);
        } finally {
            accounts.deleteById(source.id());
            accounts.deleteById(target.id());
        }
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
