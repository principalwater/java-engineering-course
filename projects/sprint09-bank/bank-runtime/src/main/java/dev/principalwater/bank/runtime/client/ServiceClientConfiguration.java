package dev.principalwater.bank.runtime.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.runtime.delivery.DeliveryConfiguration;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Conditional(ServiceClientConfiguration.Enabled.class)
@Import(DeliveryConfiguration.class)
public class ServiceClientConfiguration {
    @Bean
    ServiceClient serviceClient(
            DiscoveryClient discovery,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClients,
            ObjectMapper mapper,
            @Value("${bank.client.connect-timeout:1s}") Duration connectTimeout,
            @Value("${bank.client.read-timeout:3s}") Duration readTimeout) {
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        JdkClientHttpRequestFactory requests =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(connectTimeout)
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        requests.setReadTimeout(readTimeout);
        RestClientClientCredentialsTokenResponseClient tokenClient =
                new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(
                RestClient.builder()
                        .requestFactory(requests)
                        .messageConverters(
                                converters -> {
                                    converters.clear();
                                    converters.add(new FormHttpMessageConverter());
                                    converters.add(
                                            new OAuth2AccessTokenResponseHttpMessageConverter());
                                })
                        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                        .build());
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        registrations, authorizedClients);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials(
                                credentials -> credentials.accessTokenResponseClient(tokenClient))
                        .build());
        return new ServiceClient(
                discovery, manager, RestClient.builder().requestFactory(requests).build(), mapper);
    }

    static final class Enabled extends AnyNestedCondition {
        Enabled() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty("spring.security.oauth2.client.registration.service.client-id")
        static class Registration {}

        @ConditionalOnProperty(name = "bank.client.enabled", havingValue = "true")
        static class Explicit {}
    }
}
