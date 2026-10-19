package dev.principalwater.bank.front.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bank.login")
public record LoginProperties(
        String clientId,
        String clientSecret,
        URI issuer,
        URI tokenUri,
        URI jwkSetUri,
        URI userInfoUri) {
    @Override
    public String toString() {
        return "LoginProperties[clientId=" + clientId + ", clientSecret=REDACTED]";
    }
}
