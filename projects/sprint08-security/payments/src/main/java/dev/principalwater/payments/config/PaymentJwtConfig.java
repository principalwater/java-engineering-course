package dev.principalwater.payments.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/** Проверка access token: claims-валидатор поверх нативного декодера Boot и HTTP-клиент JWK Set. */
@Configuration(proxyBeanMethods = false)
public class PaymentJwtConfig {
    private static final String AUTHORIZED_PARTY_CLAIM = "azp";
    private static final String AUTHORIZED_PARTY = "storefront";
    private static final int JWK_CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final Duration JWK_RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    // Issuer, signature (RS256), audience, exp и nbf проверяет сам Boot по spring.security.oauth2.resourceserver.jwt.*;
    // Boot добавляет этот валидатор к своим. Стандартные проверки пропускают токен без exp/sub, а токен
    // другого OAuth-клиента с тем же audience не должен списывать деньги от имени витрины (RFC 9700, 2.3).
    // azp сравнивается как Object: claim не-строкового типа отклоняется как невалидный токен.
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
}
