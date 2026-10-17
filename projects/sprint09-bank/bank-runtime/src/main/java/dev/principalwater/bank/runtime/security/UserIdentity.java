package dev.principalwater.bank.runtime.security;

import dev.principalwater.bank.contract.Actor;
import org.springframework.security.oauth2.jwt.Jwt;

public final class UserIdentity {
    private UserIdentity() {}

    public static Actor actor(Jwt jwt) {
        String login = jwt.getClaimAsString("preferred_username");
        if (login == null
                || login.isBlank()
                || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("Token has no account identity");
        }
        return new Actor(jwt.getIssuer().toString(), jwt.getSubject(), login);
    }
}
