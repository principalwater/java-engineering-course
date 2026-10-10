package dev.principalwater.study.pair.frontend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class BackendMessageClient {
    private static final String REGISTRATION = "keycloak";
    private static final String SERVICE_PRINCIPAL = "frontend-service";
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final WebClient backend;

    public BackendMessageClient(ReactiveOAuth2AuthorizedClientManager manager, WebClient backend) {
        this.manager = manager;
        this.backend = backend;
    }

    public Mono<String> message() {
        var request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                .principal(SERVICE_PRINCIPAL).build();
        return manager.authorize(request)
                .switchIfEmpty(Mono.error(new IllegalStateException("Service authorization unavailable")))
                .flatMap(client -> backend.get().uri("/api/message")
                        .headers(headers -> headers.setBearerAuth(client.getAccessToken().getTokenValue()))
                        .retrieve().bodyToMono(String.class));
    }

    @Configuration
    static class ClientConfiguration {
        @Bean
        WebClient backendClient(WebClient.Builder builder, @Value("${backend.base-url}") String baseUrl) {
            return builder.baseUrl(baseUrl).build();
        }

        @Bean
        ReactiveOAuth2AuthorizedClientManager manager(ReactiveClientRegistrationRepository registrations,
                                                      ReactiveOAuth2AuthorizedClientService clients) {
            // Service manager кеширует M2M-токен по постоянному principal, без сессии пользователя.
            return new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, clients);
        }
    }
}
