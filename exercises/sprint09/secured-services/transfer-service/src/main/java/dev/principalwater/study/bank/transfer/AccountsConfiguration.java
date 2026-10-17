package dev.principalwater.study.bank.transfer;

import java.time.Duration;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
class AccountsConfiguration {
    @Bean
    AccountsClient accountsClient(WebClient.Builder builder, ClientRegistrationRepository registrations,
                                  OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        var oauth = new ServletOAuth2AuthorizedClientExchangeFilterFunction(manager);
        oauth.setDefaultClientRegistrationId("accounts");
        var http = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).responseTimeout(Duration.ofSeconds(3));
        return new AccountsClient(builder.baseUrl("http://127.0.0.1:19933")
                .clientConnector(new ReactorClientHttpConnector(http))
                .apply(oauth.oauth2Configuration())
                .defaultRequest(request -> request.attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.authentication(
                        new UsernamePasswordAuthenticationToken("transfer-service", null, java.util.List.of())))).build());
    }
}
