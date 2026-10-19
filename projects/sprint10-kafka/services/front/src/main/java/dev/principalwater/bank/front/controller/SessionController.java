package dev.principalwater.bank.front.controller;

import dev.principalwater.bank.contract.security.BankTokenProfile;
import dev.principalwater.bank.runtime.security.JwtAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final boolean analyticsEnabled;

    public SessionController(@Value("${bank.analytics.enabled:false}") boolean analyticsEnabled) {
        this.analyticsEnabled = analyticsEnabled;
    }

    @GetMapping("/ui/session")
    public SessionView session(@AuthenticationPrincipal OidcUser user, CsrfToken csrf) {
        boolean analyst = JwtAccess.hasRealmRole(user, BankTokenProfile.ANALYST_ROLE);
        return new SessionView(
                user.getPreferredUsername(),
                csrf.getToken(),
                csrf.getHeaderName(),
                csrf.getParameterName(),
                analyst,
                analyticsEnabled);
    }

    public record SessionView(
            String login,
            String csrfToken,
            String csrfHeader,
            String csrfParameter,
            boolean analyst,
            boolean analyticsEnabled) {}
}
