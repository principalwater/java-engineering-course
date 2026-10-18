package dev.principalwater.bank.runtime.security;

import dev.principalwater.bank.contract.security.BankTokenProfile;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bank.security")
public record JwtProperties(
        URI issuer, URI jwkSetUri, @DefaultValue(BankTokenProfile.USER_AUDIENCE) String audience) {}
