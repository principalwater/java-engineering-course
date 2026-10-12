package dev.principalwater.store.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("postgresql")
public class PostgresqlConfig {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource postgresqlDataSource(DataSourceProperties properties) {
        if (properties.getUrl() == null || !properties.getUrl().startsWith("jdbc:postgresql:")
                || properties.getUsername() == null || properties.getUsername().isBlank()
                || properties.getPassword() == null || properties.getPassword().isEmpty()) {
            throw new IllegalStateException("PostgreSQL requires its JDBC URL, username and a non-empty password");
        }
        // Config tree читает локальный file secret; для рабочего окружения нужен внешний Vault/secret manager.
        // Получение пароля не шифрует его: TLS и шифрование хранения настраиваются отдельно.
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
