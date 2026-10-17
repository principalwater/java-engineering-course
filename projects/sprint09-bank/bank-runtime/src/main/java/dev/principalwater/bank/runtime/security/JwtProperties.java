package dev.principalwater.bank.runtime.security;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bank.security")
public record JwtProperties(URI issuer, URI jwkSetUri, @DefaultValue("bank-api") String audience) {}
