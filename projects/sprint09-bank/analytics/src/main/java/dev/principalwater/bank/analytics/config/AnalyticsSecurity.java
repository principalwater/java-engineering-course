package dev.principalwater.bank.analytics.config;

import dev.principalwater.bank.runtime.security.JwtAccess;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class AnalyticsSecurity {
    @Bean
    SecurityFilterChain analyticsSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        routes ->
                                routes.requestMatchers(HttpMethod.GET, "/api/analytics/business")
                                        .access(
                                                AuthorizationManagers.allOf(
                                                        JwtAccess.user("analytics.read"),
                                                        (authentication, context) -> {
                                                            boolean analyst = false;
                                                            if (authentication.get()
                                                                    instanceof
                                                                    JwtAuthenticationToken token) {
                                                                Map<String, Object> realm =
                                                                        token.getToken()
                                                                                .getClaimAsMap(
                                                                                        "realm_access");
                                                                analyst =
                                                                        realm != null
                                                                                && realm.get(
                                                                                                "roles")
                                                                                        instanceof
                                                                                        List<?>
                                                                                                roles
                                                                                && roles.contains(
                                                                                        "analyst");
                                                            }
                                                            return new AuthorizationDecision(
                                                                    analyst);
                                                        }))
                                        .requestMatchers(HttpMethod.GET, "/api/analytics")
                                        .access(JwtAccess.user("analytics.read"))
                                        .requestMatchers(HttpMethod.POST, "/api/analytics/views")
                                        .access(JwtAccess.user("analytics.events"))
                                        .requestMatchers(
                                                HttpMethod.POST, "/internal/analytics/events")
                                        .access(
                                                JwtAccess.service(
                                                        "analytics-api",
                                                        "analytics.write",
                                                        "accounts-service"))
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .build();
    }
}
