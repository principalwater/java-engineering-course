package dev.principalwater.bank.runtime.security;

import dev.principalwater.bank.contract.Actor;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;

public final class UserIdentity {
    private UserIdentity() {}

    public static Actor actor(Jwt jwt) {
        String login = jwt.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME);
        if (login == null
                || login.isBlank()
                || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("Token has no account identity");
        }
        return new Actor(jwt.getIssuer().toString(), jwt.getSubject(), login);
    }
}
