package dev.principalwater.bank.runtime.security;

import java.util.Set;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

public final class JwtAccess {
    private JwtAccess() { }

    public static AuthorizationManager<RequestAuthorizationContext> user(String scope) {
        return (supplier, context) -> new AuthorizationDecision(supplier.get() instanceof JwtAuthenticationToken token
                && token.getToken().getAudience().contains("bank-api")
                && "bank-front".equals(token.getToken().getClaimAsString("azp")) && hasScope(token, scope));
    }

    public static AuthorizationManager<RequestAuthorizationContext> service(String audience, String scope,
                                                                           String... callers) {
        Set<String> allowed = Set.of(callers);
        return (supplier, context) -> new AuthorizationDecision(supplier.get() instanceof JwtAuthenticationToken token
                && token.getToken().getAudience().contains(audience)
                && allowed.contains(token.getToken().getClaimAsString("azp")) && hasScope(token, scope));
    }

    private static boolean hasScope(JwtAuthenticationToken token, String scope) {
        return token.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("SCOPE_" + scope));
    }
}
