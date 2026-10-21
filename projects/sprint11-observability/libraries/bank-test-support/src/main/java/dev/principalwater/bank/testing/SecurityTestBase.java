package dev.principalwater.bank.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

// Общий offline bootstrap не меняется между кейсами: JWT и JWK проверяются настоящим decoder.
@TestPropertySource(
        properties = {
            "spring.cloud.zookeeper.enabled=false",
            "spring.cloud.zookeeper.config.enabled=false",
            "spring.cloud.zookeeper.discovery.enabled=false",
            "bank.delivery.enabled=false",
            "bank.kafka.user=fixture-service",
            "bank.kafka.password=fixture-password",
            "spring.kafka.listener.auto-startup=false"
        })
public abstract class SecurityTestBase {
    protected static final JwtFixture JWT = new JwtFixture();

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("bank.security.issuer", JWT::issuer);
        properties.add("bank.security.jwk-set-uri", JWT::jwks);
    }
}
