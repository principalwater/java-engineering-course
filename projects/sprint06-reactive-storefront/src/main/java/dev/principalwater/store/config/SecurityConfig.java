package dev.principalwater.store.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain storeSecurity(ServerHttpSecurity http) {
        var csrf = new XorServerCsrfTokenRequestAttributeHandler();
        // Импорт через обычную multipart-форму сохраняет CSRF и без JavaScript; лимиты частей заданы в Boot.
        csrf.setTokenFromMultipartDataEnabled(true);
        return http.authorizeExchange(access -> access.anyExchange().permitAll())
                .csrf(config -> config.csrfTokenRequestHandler(csrf).accessDeniedHandler((exchange, failure) ->
                        Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied", failure))))
                .build();
    }
}
