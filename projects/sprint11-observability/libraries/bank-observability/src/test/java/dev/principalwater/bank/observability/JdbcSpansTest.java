package dev.principalwater.bank.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import brave.handler.MutableSpan;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Настоящий datasource-micrometer поверх подменённого DataSource и настоящие общие свойства:
// запрос внутри наблюдения становится дочерним span с текстом SQL без значений параметров, а
// опрос без родителя трассой не становится.
class JdbcSpansTest {
    private static final String SQL = "SELECT balance_minor FROM account WHERE owner = ?";
    private static final String SECRET_PARAMETER = "alice-account-4276-3800";

    @Test
    void queryUnderARequestIsAChildSpanWithoutParameterValuesAndOrphanQueryIsNotTraced() {
        TracingFixture.runner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(
                        AutoConfigurations.of(DataSourceObservationAutoConfiguration.class))
                .withUserConfiguration(Database.class)
                .withPropertyValues(
                        "spring.config.import=classpath:bank-observability.properties",
                        "spring.application.name=fixture",
                        "management.tracing.sampling.probability=1.0")
                .run(
                        context -> {
                            DataSource database = context.getBean(DataSource.class);
                            ObservationRegistry registry =
                                    context.getBean(ObservationRegistry.class);

                            query(database);
                            assertThat(TracingFixture.FINISHED).isEmpty();

                            Observation.createNotStarted("fixture.request", registry)
                                    .observe(() -> query(database));

                            MutableSpan request = finished("fixture.request");
                            MutableSpan query = finished("query");
                            assertThat(query.traceId()).isEqualTo(request.traceId());
                            assertThat(query.parentId()).isNotNull();
                            assertThat(query.tags().values()).contains(SQL);
                            assertThat(TracingFixture.FINISHED.toString())
                                    .doesNotContain(SECRET_PARAMETER);
                        });
    }

    @Test
    void poolBehindTheJdbcProxyStaysReachableForBootPoolMetrics() throws SQLException {
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(fakeDatabase());
        pool.setMaximumPoolSize(4);
        TracingFixture.runner()
                .withConfiguration(
                        AutoConfigurations.of(DataSourceObservationAutoConfiguration.class))
                .withBean(DataSource.class, () -> pool)
                .run(
                        context -> {
                            DataSource proxy = context.getBean(DataSource.class);

                            // Boot подключает метрики пула через DataSourceUnwrapper: если прокси
                            // скрывает Hikari, метрики hikaricp_* исчезают без ошибки.
                            assertThat(proxy).isNotSameAs(pool);
                            assertThat(DataSourceUnwrapper.unwrap(proxy, HikariDataSource.class))
                                    .isSameAs(pool);
                        });
        pool.close();
    }

    private static MutableSpan finished(String name) {
        List<MutableSpan> matches =
                TracingFixture.FINISHED.stream().filter(span -> name.equals(span.name())).toList();
        assertThat(matches).as("finished spans named %s", name).hasSize(1);
        return matches.getFirst();
    }

    private static void query(DataSource database) {
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setString(1, SECRET_PARAMETER);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static DataSource fakeDatabase() throws SQLException {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://database:5432/bank");
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("bank");
        when(connection.isValid(anyInt())).thenReturn(true);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        DataSource database = mock(DataSource.class);
        when(database.getConnection()).thenReturn(connection);
        return database;
    }

    @Configuration(proxyBeanMethods = false)
    static class Database {
        @Bean
        DataSource database() throws SQLException {
            return fakeDatabase();
        }
    }
}
