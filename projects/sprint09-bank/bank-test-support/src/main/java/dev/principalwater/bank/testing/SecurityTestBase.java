package dev.principalwater.bank.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class SecurityTestBase {
    protected static final JwtFixture JWT = new JwtFixture();

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("bank.security.issuer", JWT::issuer);
        properties.add("bank.security.jwk-set-uri", JWT::jwks);
    }
}
