package dev.principalwater.store.config;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("store.cart")
public record CartProperties(@DefaultValue("storefront-cart") @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String cookieName) {
}
