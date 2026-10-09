package dev.principalwater.study.data;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
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
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDao notificationDao;

    private static final int BATCH_SIZE = 100;
    private enum BatchMethod { REPOSITORY, JDBC, SIMPLE_INSERT }

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

    /** Разные API должны сохранять содержимое и выдавать уникальные ID в настоящей БД. */
    @ParameterizedTest
    @EnumSource(BatchMethod.class)
    void batchApisPreserveMessagesAndGeneratedIds(BatchMethod method) {
        String prefix = UUID.randomUUID() + ":";
        List<Notification> input = IntStream.range(0, BATCH_SIZE)
                .mapToObj(index -> new Notification(null, prefix + index)).toList();
        try {
            switch (method) {
                case REPOSITORY -> notifications.saveAll(input);
                case JDBC -> notificationDao.saveNotificationsWithJdbcTemplate(input);
                case SIMPLE_INSERT -> notificationDao.saveNotificationsWithSimpleJdbcInsert(input);
            }
            List<Notification> saved = notifications.findAll().stream()
                    .filter(item -> item.message().startsWith(prefix)).toList();
            assertThat(saved).hasSize(BATCH_SIZE);
            assertThat(saved).extracting(Notification::message)
                    .containsExactlyInAnyOrderElementsOf(input.stream().map(Notification::message).toList());
            assertThat(saved).extracting(Notification::id).doesNotContainNull().doesNotHaveDuplicates();
        } finally {
            jdbc.update("DELETE FROM notification WHERE message LIKE ?", prefix + "%");
        }
    }

    /** Сам batch не атомарен: scope должен отменить вставку перед ошибкой NOT NULL. */
    @Test
    void failedBatchRollsBackEarlierRows() {
        String marker = UUID.randomUUID().toString();
        List<Notification> input = List.of(new Notification(null, marker), new Notification(null, null));
        try {
            assertThatThrownBy(() -> notificationDao.saveNotificationsWithJdbcTemplate(input))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE message = ?", Long.class, marker))
                    .isZero();
        } finally {
            jdbc.update("DELETE FROM notification WHERE message = ?", marker);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        MySQLContainer<?> database() {
            // Контейнером управляет Spring-контекст, поэтому его срок жизни совпадает с кешем контекста.
            // Зеркало сохраняет digest и позволяет запускать CI при ограничении загрузок Docker Hub.
            return new MySQLContainer<>(DockerImageName.parse(
                    "public.ecr.aws/docker/library/mysql@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242")
                    .asCompatibleSubstituteFor("mysql"));
        }
    }
}
