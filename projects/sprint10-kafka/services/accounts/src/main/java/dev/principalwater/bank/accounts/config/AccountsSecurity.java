package dev.principalwater.bank.accounts.config;

import dev.principalwater.bank.contract.security.BankTokenProfile;
import dev.principalwater.bank.contract.security.OAuthScope;
import dev.principalwater.bank.runtime.security.BearerApiSecurity;
import dev.principalwater.bank.runtime.security.JwtAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration(proxyBeanMethods = false)
public class AccountsSecurity {
    private static final String AUDIENCE = "accounts-api";

    @Bean
    SecurityFilterChain accountsChain(HttpSecurity http) throws Exception {
        return BearerApiSecurity.stateless(http)
                .authorizeHttpRequests(this::configureRoutes)
                .build();
    }

    private void configureRoutes(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    routes) {
        routes.requestMatchers(HttpMethod.POST, "/internal/accounts/identity")
                .access(
                        JwtAccess.service(
                                AUDIENCE,
                                OAuthScope.ACCOUNTS_RESOLVE,
                                BankTokenProfile.ANALYTICS_CLIENT_ID));
        routes.requestMatchers(HttpMethod.GET, "/api/accounts/**")
                .access(JwtAccess.user(OAuthScope.ACCOUNTS_READ));
        routes.requestMatchers(HttpMethod.POST, "/api/accounts/notifications/read")
                .access(JwtAccess.user(OAuthScope.ACCOUNTS_WRITE));
        routes.requestMatchers(HttpMethod.PUT, "/api/accounts/me")
                .access(JwtAccess.user(OAuthScope.ACCOUNTS_WRITE));
        routes.requestMatchers(HttpMethod.POST, "/internal/accounts/operations")
                .access(moneyOperations());
        routes.anyRequest().denyAll();
    }

    private AuthorizationManager<RequestAuthorizationContext> moneyOperations() {
        return AuthorizationManagers.anyOf(
                JwtAccess.service(
                        AUDIENCE, OAuthScope.ACCOUNTS_CASH, BankTokenProfile.CASH_CLIENT_ID),
                JwtAccess.service(
                        AUDIENCE,
                        OAuthScope.ACCOUNTS_TRANSFER,
                        BankTokenProfile.TRANSFER_CLIENT_ID));
    }
}
