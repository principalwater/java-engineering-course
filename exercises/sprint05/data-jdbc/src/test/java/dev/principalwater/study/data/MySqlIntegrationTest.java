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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {DataJdbcApplication.class, MySqlIntegrationTest.DatabaseConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MySqlIntegrationTest {
    @Autowired
    private AccountDao accounts;
    @Autowired
    private AccountService transfers;
    @Autowired
    private JdbcTemplate jdbc;

    /** Проверяется реальный MySQL: generated key, отображение записи, DECIMAL и удаление. */
    @Test
    void crudPreservesGeneratedIdAndDecimalBalance() {
        accounts.create("O'Коннор");
        Account saved = accounts.findFirstByName("O'Коннор");
        assertThat(saved.id()).isPositive();
        try {
            assertThat(accounts.findAll()).contains(saved);
            Account updated = new Account(saved.id(), saved.name(), new BigDecimal("123.45"));
            accounts.update(updated);
            assertThat(accounts.findFirstByName(saved.name())).isEqualTo(updated);
            assertThatThrownBy(() -> accounts.findFirstByName("x' OR 1=1 -- "))
                    .isInstanceOf(EmptyResultDataAccessException.class);
            assertThat(updated.balance()).isEqualByComparingTo("123.45");
        } finally {
            jdbc.update("DELETE FROM account WHERE id = ?", saved.id());
        }
        assertThatThrownBy(() -> accounts.findFirstByName(saved.name()))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    /** Перевод либо изменяет обе записи, либо сохраняет обе после нарушения CHECK у отправителя. */
    @Test
    void transferCommitsBothChangesAndRollsBackOnOverdraft() {
        accounts.create("Отправитель");
        Account source = accounts.findFirstByName("Отправитель");
        accounts.create("Получатель");
        Account target = accounts.findFirstByName("Получатель");
        try {
            transfers.transfer(source.id(), target.id(), new BigDecimal("500.00"));
            Account committedSource = accounts.findFirstByName(source.name());
            Account committedTarget = accounts.findFirstByName(target.name());
            assertThat(committedSource.balance()).isEqualByComparingTo("9500.00");
            assertThat(committedTarget.balance()).isEqualByComparingTo("10500.00");
            assertThatThrownBy(() -> transfers.transfer(source.id(), target.id(), new BigDecimal("100000.00")))
                    .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(SQLException.class);
            assertThat(accounts.findFirstByName(source.name())).isEqualTo(committedSource);
            assertThat(accounts.findFirstByName(target.name())).isEqualTo(committedTarget);
            assertThatThrownBy(() -> transfers.transfer(source.id(), target.id(), new BigDecimal("0.001")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(accounts.findFirstByName(source.name())).isEqualTo(committedSource);
            assertThat(accounts.findFirstByName(target.name())).isEqualTo(committedTarget);
        } finally {
            jdbc.update("DELETE FROM account WHERE id = ?", source.id());
            jdbc.update("DELETE FROM account WHERE id = ?", target.id());
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
