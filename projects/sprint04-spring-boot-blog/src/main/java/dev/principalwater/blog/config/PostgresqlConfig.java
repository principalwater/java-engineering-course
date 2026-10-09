package dev.principalwater.blog.config;

import com.zaxxer.hikari.HikariDataSource;
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
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // Config tree читает локальные Docker secrets; для production нужен внешний Vault/secret manager с ротацией.
        if (properties.getUsername() == null || properties.getUsername().isBlank()
                || properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new IllegalArgumentException("PostgreSQL username and password are required");
        }
        // Boot определяет драйвер и переносит JDBC URL в настройки HikariCP.
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
