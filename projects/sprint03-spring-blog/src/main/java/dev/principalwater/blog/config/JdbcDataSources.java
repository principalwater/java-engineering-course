package dev.principalwater.blog.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;

public final class JdbcDataSources {
    private static final String H2_URL_PREFIX = "jdbc:h2:";
    private static final String POSTGRESQL_URL_PREFIX = "jdbc:postgresql:";
    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";
    private static final String H2_DEFAULT_USER = "sa";

    private JdbcDataSources() {
    }

    public static HikariDataSource create(Environment environment, String prefix) {
        String url = environment.getRequiredProperty(prefix + "_URL");
        boolean embedded = url.startsWith(H2_URL_PREFIX);
        String driver = environment.getProperty(prefix + "_DRIVER");
        String username = SecretValues.read(environment, prefix + "_USER", embedded ? H2_DEFAULT_USER : null);
        String password = SecretValues.read(environment, prefix + "_PASSWORD", embedded ? "" : null);
        if (username.isBlank() || (!embedded && password.isEmpty())) {
            throw new IllegalArgumentException("Нужны непустые JDBC credentials для " + prefix);
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        // Tomcat инициализирует DriverManager до загрузки JDBC-драйверов из WEB-INF/lib.
        config.setDriverClassName(driver == null ? driverFor(url) : driver);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(environment.getRequiredProperty(prefix + "_MAX_POOL_SIZE", Integer.class));
        config.setPoolName(environment.getRequiredProperty(prefix + "_POOL_NAME"));
        return new HikariDataSource(config);
    }

    private static String driverFor(String url) {
        if (url.startsWith(H2_URL_PREFIX)) {
            return H2_DRIVER;
        }
        if (url.startsWith(POSTGRESQL_URL_PREFIX)) {
            return POSTGRESQL_DRIVER;
        }
        throw new IllegalArgumentException("Для этого JDBC URL нужно явно задать драйвер");
    }
}
