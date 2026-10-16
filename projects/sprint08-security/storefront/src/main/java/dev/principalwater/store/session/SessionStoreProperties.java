package dev.principalwater.store.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Отдельный Redis для сессий: он не вытесняет записи и не делит учётные данные с публичным кэшем каталога.
 * Пароль приходит из файла config tree (store.sessions.password), а не из Git или параметров запуска.
 */
@ConfigurationProperties("store.sessions")
public record SessionStoreProperties(@DefaultValue("") String host, @DefaultValue("6379") int port,
                                     @DefaultValue("") String username, @DefaultValue("") String password,
                                     @DefaultValue("store:session:storefront") String namespace,
                                     @DefaultValue("250ms") Duration connectTimeout,
                                     @DefaultValue("500ms") Duration commandTimeout) {
    private static final int MAX_PORT = 65535;
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(5);

    // Профиль shared-sessions без полной настройки не стартует: сессии не переходят молча в память процесса.
    public SessionStoreProperties {
        if (host.isBlank()) {
            throw new IllegalArgumentException("Session store host (store.sessions.host) is required");
        }
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("Session store port must be between 1 and " + MAX_PORT);
        }
        if (password.isEmpty()) {
            throw new IllegalArgumentException("Session store requires a non-empty password (store.sessions.password)");
        }
        if (!namespace.matches("[A-Za-z0-9_:\\-]{1,64}")) {
            throw new IllegalArgumentException("Session namespace must contain 1 to 64 letters, digits, colons, underscores or hyphens");
        }
        requireBounded("connect timeout", connectTimeout);
        requireBounded("command timeout", commandTimeout);
    }

    private static void requireBounded(String name, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Session store " + name + " must be positive and at most " + MAX_TIMEOUT);
        }
    }

    @Override
    public String toString() {
        return "SessionStoreProperties[host=" + host + ", port=" + port + ", namespace=" + namespace + "]";
    }
}
