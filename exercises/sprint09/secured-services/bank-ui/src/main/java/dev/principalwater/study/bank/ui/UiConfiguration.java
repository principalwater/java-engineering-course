package dev.principalwater.study.bank.ui;

import java.time.Duration;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
class UiConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, ClientRegistrationRepository registrations) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return http.authorizeHttpRequests(auth -> auth.requestMatchers("/", "/style.css", "/error", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> login.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .defaultSuccessUrl("/transfer", true))
                .logout(logout -> logout.logoutSuccessUrl("/")).build();
    }

    @Bean
    WebClient gatewayClient(WebClient.Builder builder, OAuth2AuthorizedClientManager manager) {
        var oauth = new ServletOAuth2AuthorizedClientExchangeFilterFunction(manager);
        oauth.setDefaultClientRegistrationId("keycloak");
        var http = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).responseTimeout(Duration.ofSeconds(3));
        return builder.baseUrl("http://127.0.0.1:19931")
                .clientConnector(new ReactorClientHttpConnector(http)).apply(oauth.oauth2Configuration()).build();
    }
}
