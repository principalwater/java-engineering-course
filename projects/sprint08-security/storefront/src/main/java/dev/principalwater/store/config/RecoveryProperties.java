package dev.principalwater.store.config;

import dev.principalwater.store.controller.StorePaths;
import dev.principalwater.store.model.AccountRules;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Ссылка из письма строится только из этой настройки: заголовок Host запроса не участвует в адресе.
@ConfigurationProperties("store.recovery")
public record RecoveryProperties(@DefaultValue("true") boolean enabled, @DefaultValue("") String publicBaseUrl,
                                 @DefaultValue("") String sender) {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public RecoveryProperties {
        publicBaseUrl = validBaseUrl(publicBaseUrl);
        if (!sender.isEmpty() && !AccountRules.validEmail(sender)) {
            throw new IllegalArgumentException("Recovery sender must be a valid email address");
        }
    }

    public String resetLink(String token) {
        return publicBaseUrl + StorePaths.RECOVERY_RESET + "?token=" + token;
    }

    // HTTP допустим только для loopback: учебный запуск без TLS, но токен в публичной сети идёт лишь по HTTPS.
    private static String validBaseUrl(String value) {
        if (value.isEmpty()) {
            return value;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Recovery public base URL is not a valid URI", failure);
        }
        boolean secure = "https".equals(uri.getScheme());
        boolean loopback = "http".equals(uri.getScheme()) && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost());
        if (!(secure || loopback) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Recovery public base URL must be an absolute https URL without credentials, query or fragment");
        }
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
