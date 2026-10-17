package dev.principalwater.bank.accounts.config;

import dev.principalwater.bank.runtime.security.JwtAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class AccountsSecurity {
    @Bean
    SecurityFilterChain accountsChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .authorizeHttpRequests(
                        access ->
                                access.requestMatchers(
                                                HttpMethod.POST, "/internal/accounts/identity")
                                        .access(
                                                JwtAccess.service(
                                                        "accounts-api",
                                                        "accounts.resolve",
                                                        "analytics-service"))
                                        .requestMatchers(HttpMethod.GET, "/api/accounts/**")
                                        .access(JwtAccess.user("accounts.read"))
                                        .requestMatchers(HttpMethod.PUT, "/api/accounts/me")
                                        .access(JwtAccess.user("accounts.write"))
                                        .requestMatchers(
                                                HttpMethod.POST, "/internal/accounts/operations")
                                        .access(
                                                AuthorizationManagers.anyOf(
                                                        JwtAccess.service(
                                                                "accounts-api",
                                                                "accounts.cash",
                                                                "cash-service"),
                                                        JwtAccess.service(
                                                                "accounts-api",
                                                                "accounts.transfer",
                                                                "transfer-service")))
                                        .anyRequest()
                                        .denyAll())
                .build();
    }
}
