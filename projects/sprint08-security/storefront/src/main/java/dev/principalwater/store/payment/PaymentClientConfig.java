package dev.principalwater.store.payment;

import dev.principalwater.store.payment.generated.api.PaymentsApi;
import dev.principalwater.store.payment.generated.client.ApiClient;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.RemoveAuthorizedClientReactiveOAuth2AuthorizationFailureHandler;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentClientConfig {
    private static final String CLIENT_REGISTRATION = "payments";
    private static final String SERVICE_PRINCIPAL = "storefront";

    @Bean
    public ReactiveOAuth2AuthorizedClientManager paymentAuthorization(ReactiveClientRegistrationRepository registrations,
            ReactiveOAuth2AuthorizedClientService clients, ConnectionProvider paymentsConnections, LoopResources storeHttpLoops) {
        var tokenClient = new WebClientReactiveClientCredentialsTokenResponseClient();
        var transport = HttpClient.create(paymentsConnections).runOn(storeHttpLoops)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(Duration.ofSeconds(2));
        tokenClient.setWebClient(WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024)).build());
        var provider = new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        provider.setClockSkew(Duration.ofSeconds(30));
        var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(provider);
        // Один application principal для HTTP-запросов и фонового recovery; токен не привязан к сессии покупателя.
        return request -> manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(CLIENT_REGISTRATION)
                .principal(SERVICE_PRINCIPAL).build());
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider paymentsConnections() {
        return ConnectionProvider.builder("payments").maxConnections(8).pendingAcquireMaxCount(32)
                .pendingAcquireTimeout(Duration.ofSeconds(1)).maxIdleTime(Duration.ofSeconds(20)).build();
    }
    @Bean
    public PaymentsApi paymentsApi(PaymentProperties properties, WebClient.Builder builder,
                                    ConnectionProvider paymentsConnections, LoopResources storeHttpLoops,
                                    ReactiveOAuth2AuthorizedClientManager paymentAuthorization,
                                    ReactiveOAuth2AuthorizedClientService clients) {
        var transport = HttpClient.create(paymentsConnections).runOn(storeHttpLoops)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(Duration.ofSeconds(3));
        var oauth = new ServerOAuth2AuthorizedClientExchangeFilterFunction(paymentAuthorization);
        oauth.setDefaultClientRegistrationId(CLIENT_REGISTRATION);
        oauth.setAuthorizationFailureHandler(new RemoveAuthorizedClientReactiveOAuth2AuthorizationFailureHandler(
                (registration, principal, attributes) -> clients.removeAuthorizedClient(registration, SERVICE_PRINCIPAL)));
        // Отдельный клиент обслуживает только платежи: Bearer не уходит в ClickHouse или другой внешний API.
        var web = builder.clone().filter(oauth).clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024)).build();
        var client = new ApiClient(web).setBasePath(properties.url().toString());
        return new PaymentsApi(client);
    }
}
