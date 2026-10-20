package dev.principalwater.bank.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;

class DeployedDataSourceBindingTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"accounts", "cash", "transfer", "notifications", "analytics"})
    void actualSharedConfigurationBindsPoolWithoutOpeningAConnection(String service)
            throws IOException {
        Map<String, Object> environment =
                Map.of("SPRING_APPLICATION_NAME", service, "SPRING_DATASOURCE_USERNAME", service);
        StandardEnvironment settings = new StandardEnvironment();
        settings.getPropertySources()
                .replace(
                        "systemEnvironment",
                        new SystemEnvironmentPropertySource("systemEnvironment", environment));
        settings.getPropertySources()
                .addLast(new ResourcePropertySource("classpath:bank-common.properties"));
        try (BindingProbe pool = new BindingProbe()) {
            Binder.get(settings).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
            assertThat(pool.connectionsRequested).isZero();
            assertThat(pool.isRunning()).isFalse();
            assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
            assertThat(pool.getSchema()).isEqualTo(service);
            assertThat(pool.getConnectionTimeout()).isPositive().isLessThanOrEqualTo(5000);
            assertThat(pool.getDataSourceProperties()).containsKey("options");
        }
    }

    private static final class BindingProbe extends HikariDataSource {
        private int connectionsRequested;

        @Override
        public Connection getConnection() throws SQLException {
            connectionsRequested++;
            throw new SQLException("Configuration binding must not open a connection");
        }
    }
}
