package dev.principalwater.bank.gateway.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
public class GatewaySecurity {
    @Bean
    ReactiveJwtDecoder gatewayDecoder(
            @Value("${bank.security.issuer}") String issuer,
            @Value("${bank.security.jwk-set-uri}") String jwks) {
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withJwkSetUri(jwks)
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .webClient(
                                WebClient.builder()
                                        .clientConnector(
                                                new ReactorClientHttpConnector(
                                                        HttpClient.create()
                                                                .option(
                                                                        ChannelOption
                                                                                .CONNECT_TIMEOUT_MILLIS,
                                                                        1000)
                                                                .responseTimeout(
                                                                        Duration.ofSeconds(3))))
                                        .build())
                        .build();
        OAuth2TokenValidator<Jwt> userAudience =
                jwt ->
                        jwt.getAudience().contains("bank-api")
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Invalid audience", null));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer), userAudience));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain gatewayChain(ServerHttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .authorizeExchange(
                        access ->
                                access.pathMatchers("/api/**")
                                        .access(
                                                (authentication, context) ->
                                                        authentication.map(
                                                                auth ->
                                                                        new AuthorizationDecision(
                                                                                isBankFront(auth))))
                                        .anyExchange()
                                        .denyAll())
                .build();
    }

    private boolean isBankFront(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token
                && "bank-front".equals(token.getToken().getClaimAsString("azp"));
    }
}
