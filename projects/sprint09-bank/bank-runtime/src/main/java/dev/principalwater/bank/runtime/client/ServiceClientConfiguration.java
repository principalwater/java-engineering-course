package dev.principalwater.bank.runtime.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.runtime.delivery.DeliveryConfiguration;
import dev.principalwater.bank.runtime.security.OAuthHttpRequests;
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
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
            @Value(
                            "${bank.client.connect-timeout:"
                                    + OAuthHttpRequests.DEFAULT_CONNECT_TIMEOUT
                                    + "}")
                    Duration connectTimeout,
            @Value("${bank.client.read-timeout:" + OAuthHttpRequests.DEFAULT_READ_TIMEOUT + "}")
                    Duration readTimeout) {
        JdkClientHttpRequestFactory requests =
                OAuthHttpRequests.create(connectTimeout, readTimeout);
        RestClientClientCredentialsTokenResponseClient tokenClient =
                new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(OAuthHttpRequests.tokenClient(requests));
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
