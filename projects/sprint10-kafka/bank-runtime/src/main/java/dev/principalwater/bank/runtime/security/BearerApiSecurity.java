package dev.principalwater.bank.runtime.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

public final class BearerApiSecurity {
    private BearerApiSecurity() {}

    public static HttpSecurity stateless(HttpSecurity http) throws Exception {
        // Эти API принимают только bearer: browser session и CSRF остаются у Front.
        return http.csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
    }
}
