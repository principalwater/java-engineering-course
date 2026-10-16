package dev.principalwater.payments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import reactor.core.publisher.Mono;

/** Цепочка фильтров: правила доступа по scope; проверка токена и ответы об отказе заданы в соседних конфигурациях. */
@Configuration(proxyBeanMethods = false)
public class PaymentSecurityConfig {
    private static final String BALANCE_PATH = "/api/v1/accounts/*/balance";
    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String READ_SCOPE_AUTHORITY = "SCOPE_payments.read";
    private static final String WRITE_SCOPE_AUTHORITY = "SCOPE_payments.write";

    @Bean
    public SecurityWebFilterChain paymentSecurity(ServerHttpSecurity http, ServerAuthenticationEntryPoint authenticationEntryPoint,
                                                  ServerAccessDeniedHandler accessDeniedHandler) {
        return http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                // CSRF выключен осознанно: идентичность передаётся только явным Authorization: Bearer, сервис не
                // читает cookie/session и сам их не создаёт, поэтому браузер не может приложить чужие права к запросу.
                .csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(access -> access.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.GET, BALANCE_PATH).hasAuthority(READ_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.POST, PAYMENTS_PATH).hasAuthority(WRITE_SCOPE_AUTHORITY)
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenConverter(singleAuthorizationHeader())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    // Прокси могут по-разному выбирать первый/последний header; неоднозначный credential отвергается.
    private static ServerAuthenticationConverter singleAuthorizationHeader() {
        var bearer = new ServerBearerTokenAuthenticationConverter();
        return exchange -> exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).size() > 1
                ? Mono.error(new OAuth2AuthenticationException(new OAuth2Error(BearerTokenErrorCodes.INVALID_REQUEST)))
                : bearer.convert(exchange);
    }
}
