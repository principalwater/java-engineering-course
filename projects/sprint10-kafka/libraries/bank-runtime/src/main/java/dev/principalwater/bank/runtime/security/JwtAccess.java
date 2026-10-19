package dev.principalwater.bank.runtime.security;

import dev.principalwater.bank.contract.security.BankTokenProfile;
import dev.principalwater.bank.contract.security.OAuthScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

public final class JwtAccess {
    private JwtAccess() {}

    public static AuthorizationManager<RequestAuthorizationContext> user(OAuthScope scope) {
        return (supplier, context) ->
                new AuthorizationDecision(
                        supplier.get() instanceof JwtAuthenticationToken token
                                && isUser(token, scope));
    }

    public static AuthorizationManager<RequestAuthorizationContext> service(
            String audience, OAuthScope scope, String... callers) {
        Set<String> allowed = Set.of(callers);
        return (supplier, context) ->
                new AuthorizationDecision(
                        supplier.get() instanceof JwtAuthenticationToken token
                                && token.getToken().getAudience().contains(audience)
                                && allowed.contains(
                                        token.getToken().getClaimAsString(IdTokenClaimNames.AZP))
                                && hasScope(token, scope));
    }

    public static AuthorizationManager<RequestAuthorizationContext> realmRole(String role) {
        return (supplier, context) ->
                new AuthorizationDecision(
                        supplier.get() instanceof JwtAuthenticationToken token
                                && hasRealmRole(token.getToken(), role));
    }

    public static boolean hasRealmRole(ClaimAccessor claims, String role) {
        Map<String, Object> realm = claims.getClaimAsMap(BankTokenProfile.REALM_ACCESS_CLAIM);
        return realm != null
                && realm.get(BankTokenProfile.REALM_ROLES_CLAIM) instanceof List<?> roles
                && roles.contains(role);
    }

    private static boolean isUser(JwtAuthenticationToken token, OAuthScope scope) {
        return token.getToken().getAudience().contains(BankTokenProfile.USER_AUDIENCE)
                && BankTokenProfile.FRONT_CLIENT_ID.equals(
                        token.getToken().getClaimAsString(IdTokenClaimNames.AZP))
                && hasScope(token, scope);
    }

    private static boolean hasScope(JwtAuthenticationToken token, OAuthScope scope) {
        return token.getAuthorities().stream()
                .anyMatch(
                        authority -> authority.getAuthority().equals("SCOPE_" + scope.getValue()));
    }
}
