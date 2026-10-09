package ru.practicum.spring.data.liquibasehomework;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.FastCheckService;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.spring.data.liquibasehomework.domain.entity.Order;
import ru.practicum.spring.data.liquibasehomework.domain.entity.Product;
import ru.practicum.spring.data.liquibasehomework.domain.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationIntegrationTest {
    private static final String SCHEMA = "yandex_practicum_homework";
    private static final String TABLE_COUNT = "select count(*) from information_schema.tables "
            + "where table_schema = 'YANDEX_PRACTICUM_HOMEWORK' "
            + "and table_name in ('USERS', 'PRODUCTS', 'ORDERS', 'ORDER_PRODUCTS')";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private SpringLiquibase springLiquibase;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void migrationsSupportEntitiesConstraintsAndRollback() throws Exception {
        // Проверяем DDL на реальной H2: Hibernate только валидирует схему.
        assertThat(jdbc.queryForObject(TABLE_COUNT, Integer.class)).isEqualTo(4);
        transaction.executeWithoutResult(status -> {
            User user = new User(null, "reader", "test-only", "reader@example.test");
            Product product = new Product(null, "Java", 42.5, "Учебный пример");
            entityManager.persist(user);
            entityManager.persist(product);
            Order order = new Order(null, user, List.of(product), LocalDateTime.of(2026, 10, 9, 12, 0));
            entityManager.persist(order);
            entityManager.flush();
            entityManager.clear();
            Order saved = entityManager.find(Order.class, order.getId());
            assertThat(saved.getUser().getUsername()).isEqualTo("reader");
            assertThat(saved.getProducts()).extracting(Product::getName).containsExactly("Java");
        });
        assertThatThrownBy(() -> jdbc.update("insert into users values (?, ?, ?, ?)",
                999L, "reader", "test-only", "other@example.test"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into order_products values (?, ?)", 999L, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        long applied = jdbc.queryForObject("select count(*) from databasechangelog", Long.class);
        springLiquibase.afterPropertiesSet();
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Long.class)).isEqualTo(applied);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                new JdbcConnection(dataSource.getConnection()));
        database.setDefaultSchemaName(SCHEMA);
        database.setLiquibaseSchemaName(SCHEMA);
        try (Liquibase liquibase = new Liquibase("db/changelog/liquibase/db.changelog-master.xml",
                new ClassLoaderResourceAccessor(), database)) {
            liquibase.rollback(Math.toIntExact(applied), new Contexts(), new LabelExpression());
            assertThat(jdbc.queryForObject(TABLE_COUNT, Integer.class)).isZero();
        }
        // В одном JVM fast-check помнит состояние до rollback; отдельный CLI-процесс его не наследует.
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        springLiquibase.afterPropertiesSet();
        assertThat(jdbc.queryForObject(TABLE_COUNT, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
    }
}
