package dev.principalwater.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.payments.controller.PaymentProblems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/** Ответы security-слоя: 401 и 403 с challenge по RFC 6750, 503 при недоступном JWK Set. */
@Configuration(proxyBeanMethods = false)
public class PaymentSecurityErrorConfig {
    private static final String BEARER_CHALLENGE = "Bearer";
    private static final String AUTHENTICATION_REQUIRED = "Service authentication is required";
    private static final String ACCESS_DENIED = "Access denied";
    private static final String KEYS_UNAVAILABLE = "Authorization keys are temporarily unavailable";
    private static final String RETRY_AFTER_SECONDS = "1";
    // Раньше ErrorWebExceptionHandler Boot (-1): иначе сбой ключей получил бы общий ответ 500, а не 503.
    private static final int KEY_FAILURE_HANDLER_ORDER = -2;

    @Bean
    public ServerAuthenticationEntryPoint paymentAuthenticationEntryPoint(ObjectMapper json) {
        return (exchange, failure) -> unauthorized(exchange, failure, json);
    }

    @Bean
    public ServerAccessDeniedHandler paymentAccessDeniedHandler(ObjectMapper json) {
        return (exchange, failure) -> forbidden(exchange, json);
    }

    @Bean
    @Order(KEY_FAILURE_HANDLER_ORDER)
    public WebExceptionHandler authorizationKeyFailures(ObjectMapper json) {
        // Nimbus оборачивает сбой HTTP-клиента в IllegalStateException: недоступность ключей не равна неверной подписи.
        return (exchange, failure) -> {
            if (failure instanceof IllegalStateException && failure.getCause() instanceof WebClientException) {
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
                return PaymentProblems.write(HttpStatus.SERVICE_UNAVAILABLE, KEYS_UNAVAILABLE, exchange, json);
            }
            return Mono.error(failure);
        };
    }

    // RFC 6750, 3: без токена challenge пустой, для отвергнутого токена добавляется error="invalid_token".
    private static Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException failure, ObjectMapper json) {
        String challenge = failure instanceof OAuth2AuthenticationException rejected
                ? bearerChallenge(rejected.getError().getErrorCode()) : BEARER_CHALLENGE;
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        return PaymentProblems.write(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, exchange, json);
    }

    // Сюда попадает только аутентифицированный токен (аноним получает 401), поэтому причина всегда scope.
    private static Mono<Void> forbidden(ServerWebExchange exchange, ObjectMapper json) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge(BearerTokenErrorCodes.INSUFFICIENT_SCOPE));
        return PaymentProblems.write(HttpStatus.FORBIDDEN, ACCESS_DENIED, exchange, json);
    }

    private static String bearerChallenge(String errorCode) {
        return BEARER_CHALLENGE + " error=\"" + errorCode + "\"";
    }
}
