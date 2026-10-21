package dev.principalwater.bank.front.config;

import dev.principalwater.bank.runtime.security.HttpRequests;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class OAuthHttpConfiguration {
    private final JdkClientHttpRequestFactory requests;
    private final RestTemplateBuilder restTemplates;
    private final RestClient.Builder restClients;

    // Внедрённые builders Boot несут наблюдение клиента (метрики и trace span запросов к IdP);
    // общая JDK-фабрика сохраняет тайм-ауты и пул соединений.
    public OAuthHttpConfiguration(
            RestTemplateBuilder restTemplates, RestClient.Builder restClients) {
        requests = HttpRequests.create();
        this.restTemplates = restTemplates;
        this.restClients = restClients;
    }

    @Bean
    RestClientAuthorizationCodeTokenResponseClient authorizationCodeClient() {
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(tokenClient());
        return client;
    }

    @Bean
    OAuth2AuthorizedClientManager userAuthorizedClients(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients) {
        RestClientRefreshTokenTokenResponseClient refresh =
                new RestClientRefreshTokenTokenResponseClient();
        refresh.setRestClient(tokenClient());
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken(config -> config.accessTokenResponseClient(refresh))
                        .build());
        return manager;
    }

    @Bean
    OidcUserService bankOidcUserService() {
        DefaultOAuth2UserService userInfo = new DefaultOAuth2UserService();
        userInfo.setRestOperations(
                restTemplates
                        .requestFactory(() -> requests)
                        .errorHandler(new OAuth2ErrorResponseErrorHandler())
                        .build());
        OidcUserService oidc = new OidcUserService();
        oidc.setOauth2UserService(userInfo);
        return oidc;
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoders(
            ClientRegistrationRepository registrations) {
        ClientRegistration bank = registrations.findByRegistrationId(FrontOAuth.REGISTRATION_ID);
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(bank.getProviderDetails().getJwkSetUri())
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .restOperations(restTemplates.requestFactory(() -> requests).build())
                        .build();
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new OidcIdTokenValidator(bank), new JwtTimestampValidator()));
        // Единственный registration использует общий кеш JWK, а не новый decoder на каждый вход.
        return registration -> checkedDecoder(registration, bank, decoder);
    }

    private JwtDecoder checkedDecoder(
            ClientRegistration requested, ClientRegistration bank, JwtDecoder decoder) {
        if (!bank.getRegistrationId().equals(requested.getRegistrationId())) {
            throw new IllegalArgumentException("Unknown OIDC registration");
        }
        return decoder;
    }

    private RestClient tokenClient() {
        return restClients
                .clone()
                .requestFactory(requests)
                .messageConverters(
                        converters -> {
                            converters.clear();
                            converters.add(new FormHttpMessageConverter());
                            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                        })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
