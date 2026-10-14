package dev.principalwater.store.config;

import org.springframework.boot.autoconfigure.r2dbc.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("postgresql")
public class PostgresqlConfig {
    @Bean
    public ConnectionFactoryOptionsBuilderCustomizer postgresqlOptions(R2dbcProperties properties) {
        if (properties.getUrl() == null || !properties.getUrl().startsWith("r2dbc:postgresql:")
                || properties.getUsername() == null || properties.getUsername().isBlank()
                || properties.getPassword() == null || properties.getPassword().isEmpty()) {
            throw new IllegalStateException("PostgreSQL requires its R2DBC URL, username and a non-empty password");
        }
        // Config tree читает file secret; для рабочего окружения нужен внешний Vault/secret manager.
        // Получение пароля не шифрует его: TLS и шифрование хранения настраиваются отдельно.
        return builder -> { };
    }
}
