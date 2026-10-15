package dev.principalwater.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.payments.controller.PaymentProblems;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class PaymentSecurityConfig {
    private static final String BALANCE_PATH = "/api/v1/accounts/*/balance";
    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String READ_SCOPE_AUTHORITY = "SCOPE_payments.read";
    private static final String WRITE_SCOPE_AUTHORITY = "SCOPE_payments.write";
    private static final String AUTHORIZED_PARTY_CLAIM = "azp";
    private static final String AUTHORIZED_PARTY = "storefront";
    private static final String BEARER_CHALLENGE = "Bearer";
    private static final String AUTHENTICATION_REQUIRED = "Service authentication is required";
    private static final String ACCESS_DENIED = "Access denied";
    private static final int JWK_CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final Duration JWK_RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    // Issuer, signature (RS256), audience, exp и nbf проверяет сам Boot по spring.security.oauth2.resourceserver.jwt.*;
    // Boot добавляет этот валидатор к своим. Стандартные проверки пропускают токен без exp/sub, а токен
    // другого OAuth-клиента с тем же audience не должен списывать деньги от имени витрины (RFC 9700, 2.3).
    @Bean
    public OAuth2TokenValidator<Jwt> paymentTokenClaims() {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
                new JwtClaimValidator<Object>(AUTHORIZED_PARTY_CLAIM, AUTHORIZED_PARTY::equals));
    }

    // WebClient по умолчанию ждёт JWK Set без предела на ответ (connect — 30 с у Netty): зависший Keycloak
    // держал бы запрос платежа и admission-слот. Пределы равны таймаутам БД (2 с соединение, 3 с запрос),
    // loop-ресурсы общие с сервером и драйвером БД.
    @Bean
    public JwkSetUriReactiveJwtDecoderBuilderCustomizer paymentJwkSetClient(ReactorResourceFactory paymentHttpResources) {
        var connector = new ReactorClientHttpConnector(paymentHttpResources,
                client -> client.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, JWK_CONNECT_TIMEOUT_MILLIS)
                        .responseTimeout(JWK_RESPONSE_TIMEOUT));
        return builder -> builder.webClient(WebClient.builder().clientConnector(connector).build());
    }

    @Bean
    @Order(-2)
    public WebExceptionHandler authorizationKeyFailures(ObjectMapper json) {
        // Nimbus оборачивает сбой HTTP-клиента в IllegalStateException: недоступность ключей не равна неверной подписи.
        return (exchange, failure) -> {
            if (failure instanceof IllegalStateException && failure.getCause() instanceof WebClientException) {
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
                return PaymentProblems.write(HttpStatus.SERVICE_UNAVAILABLE,
                        "Authorization keys are temporarily unavailable", exchange, json);
            }
            return Mono.error(failure);
        };
    }

    @Bean
    public SecurityWebFilterChain paymentSecurity(ServerHttpSecurity http, ObjectMapper json) {
        var bearer = new ServerBearerTokenAuthenticationConverter();
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
                        // Прокси могут по-разному выбирать первый/последний header; неоднозначный credential отвергается.
                        .bearerTokenConverter(exchange -> exchange.getRequest().getHeaders()
                                .getOrEmpty(HttpHeaders.AUTHORIZATION).size() > 1
                                ? Mono.error(new OAuth2AuthenticationException(new OAuth2Error("invalid_request")))
                                : bearer.convert(exchange))
                        .authenticationEntryPoint((exchange, failure) -> unauthorized(exchange, failure, json))
                        .accessDeniedHandler((exchange, failure) -> forbidden(exchange, json))
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    // RFC 6750, 3: без токена challenge пустой, для отвергнутого токена добавляется error="invalid_token".
    private static Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException failure, ObjectMapper json) {
        String challenge = failure instanceof OAuth2AuthenticationException rejected
                ? BEARER_CHALLENGE + " error=\"" + rejected.getError().getErrorCode() + "\"" : BEARER_CHALLENGE;
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        return PaymentProblems.write(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, exchange, json);
    }

    // Сюда попадает только аутентифицированный токен (аноним получает 401), поэтому причина всегда scope.
    private static Mono<Void> forbidden(ServerWebExchange exchange, ObjectMapper json) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE + " error=\"insufficient_scope\"");
        return PaymentProblems.write(HttpStatus.FORBIDDEN, ACCESS_DENIED, exchange, json);
    }
}
