package dev.principalwater.store.config;

import dev.principalwater.store.model.AccountRules;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("store.seller")
public record SellerProperties(@DefaultValue("seller") String username, @DefaultValue("") String bootstrapPassword,
                               @DefaultValue("") String email) {
    public SellerProperties {
        if (!bootstrapPassword.isEmpty() && !AccountRules.validCredentials(username, bootstrapPassword)) {
            throw new IllegalArgumentException("Seller bootstrap credentials do not meet the account rules");
        }
        // Контакт продавца вносит оператор: без него продавец не сможет восстановить доступ.
        if (!email.isEmpty() && !AccountRules.validEmail(email)) {
            throw new IllegalArgumentException("Seller contact email does not meet the account rules");
        }
    }
    @Override public String toString() { return "SellerProperties[username=" + username + "]"; }
}
