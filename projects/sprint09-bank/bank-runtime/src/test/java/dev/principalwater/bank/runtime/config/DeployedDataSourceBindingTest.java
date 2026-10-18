package dev.principalwater.bank.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.yaml.snakeyaml.Yaml;

class DeployedDataSourceBindingTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("deployedEnvironments")
    void actualComposeEnvironmentBindsPoolWithoutOpeningAConnection(
            String service, Map<String, Object> environment) throws IOException {
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

    private static Stream<Arguments> deployedEnvironments() throws IOException {
        Map<?, ?> core = compose("compose.yaml");
        Map<?, ?> analytics = compose("compose.analytics.yaml");
        return Stream.of("accounts", "cash", "transfer", "notifications", "analytics")
                .map(
                        service -> {
                            Map<String, Object> environment = new LinkedHashMap<>();
                            appendEnvironment(core, service, environment);
                            appendEnvironment(analytics, service, environment);
                            return Arguments.of(service, environment);
                        });
    }

    private static Map<?, ?> compose(String file) throws IOException {
        try (Reader source = Files.newBufferedReader(Path.of("..", file))) {
            return new Yaml().load(source);
        }
    }

    private static void appendEnvironment(
            Map<?, ?> compose, String name, Map<String, Object> target) {
        Map<?, ?> services = (Map<?, ?>) compose.get("services");
        if (services.get(name) instanceof Map<?, ?> service
                && service.get("environment") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> target.put(key.toString(), value.toString()));
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
