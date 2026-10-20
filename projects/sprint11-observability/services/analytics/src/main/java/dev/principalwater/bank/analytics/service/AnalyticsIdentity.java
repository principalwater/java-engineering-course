package dev.principalwater.bank.analytics.service;

import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.contract.OwnerReference;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.security.UserIdentity;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsIdentity {
    private final ServiceClient accounts;

    public AnalyticsIdentity(ServiceClient accounts) {
        this.accounts = accounts;
    }

    public UUID owner(Jwt jwt) {
        OwnerReference reference =
                accounts.post(
                        "accounts",
                        "/internal/accounts/identity",
                        UserIdentity.actor(jwt),
                        OwnerReference.class);
        if (reference == null || reference.ownerId() == null) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.IDENTITY);
        }
        return reference.ownerId();
    }
}
