package dev.principalwater.bank.runtime.security;

import dev.principalwater.bank.contract.security.BankTokenProfile;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class ApiJwtConfiguration {
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        JdkClientHttpRequestFactory requests = HttpRequests.create();
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .restOperations(new RestTemplate(requests))
                        .build();
        OAuth2TokenValidator<Jwt> audience =
                jwt -> {
                    List<String> audiences = jwt.getAudience();
                    return audiences != null
                                    && (audiences.contains(BankTokenProfile.USER_AUDIENCE)
                                            || audiences.contains(properties.audience()))
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                    new OAuth2Error(
                                            OAuth2ErrorCodes.INVALID_TOKEN,
                                            "Invalid audience",
                                            null));
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(properties.issuer().toString()),
                        audience));
        return decoder;
    }
}
