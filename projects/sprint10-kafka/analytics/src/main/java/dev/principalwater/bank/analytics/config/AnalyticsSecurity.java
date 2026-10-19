package dev.principalwater.bank.analytics.config;

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
public class AnalyticsSecurity {
    @Bean
    SecurityFilterChain analyticsSecurityFilterChain(HttpSecurity http) throws Exception {
        return BearerApiSecurity.stateless(http)
                .authorizeHttpRequests(this::configureRoutes)
                .build();
    }

    private void configureRoutes(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    routes) {
        routes.requestMatchers(HttpMethod.GET, "/api/analytics/business").access(businessRead());
        routes.requestMatchers(HttpMethod.GET, "/api/analytics")
                .access(JwtAccess.user(OAuthScope.ANALYTICS_READ));
        routes.requestMatchers(HttpMethod.POST, "/api/analytics/views")
                .access(JwtAccess.user(OAuthScope.ANALYTICS_EVENTS));
        routes.requestMatchers(HttpMethod.POST, "/api/analytics/activities")
                .access(JwtAccess.user(OAuthScope.ANALYTICS_EVENTS));
        routes.anyRequest().denyAll();
    }

    private AuthorizationManager<RequestAuthorizationContext> businessRead() {
        return AuthorizationManagers.allOf(
                JwtAccess.user(OAuthScope.ANALYTICS_READ),
                JwtAccess.realmRole(BankTokenProfile.ANALYST_ROLE));
    }
}
