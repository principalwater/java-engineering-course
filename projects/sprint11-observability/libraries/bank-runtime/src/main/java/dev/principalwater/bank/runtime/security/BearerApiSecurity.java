package dev.principalwater.bank.runtime.security;

import dev.principalwater.bank.contract.ProbePaths;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

public final class BearerApiSecurity {
    private BearerApiSecurity() {}

    public static HttpSecurity stateless(HttpSecurity http) throws Exception {
        // Эти API принимают только bearer: browser session и CSRF остаются у Front.
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(BearerApiSecurity::allowManagement)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
    }

    // Метрики открываются точным GET-путём без токена: Prometheus не получает JWT, а доступ
    // ограничивают сетевая политика и внешний прокси. Остальные пути Actuator остаются закрытыми.
    private static void allowManagement(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    routes) {
        routes.requestMatchers(
                        HttpMethod.GET,
                        ProbePaths.LIVENESS,
                        ProbePaths.READINESS,
                        ProbePaths.PROMETHEUS)
                .permitAll();
    }
}
