package dev.principalwater.bank.gateway.config;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.transport.NameResolverProvider;

@Configuration(proxyBeanMethods = false)
public class RoutesConfiguration {
    private static final Duration DNS_CACHE_TTL = Duration.ofSeconds(5);
    private static final Duration DNS_FAILURE_CACHE_TTL = Duration.ofSeconds(1);

    @Bean
    HttpClientCustomizer discoveryDnsCache() {
        return client -> client.resolver(this::configureDiscoveryDns);
    }

    private void configureDiscoveryDns(NameResolverProvider.NameResolverSpec resolver) {
        // Короткий кеш позволяет быстро подхватить смену IP при пересоздании сервиса.
        resolver.cacheMaxTimeToLive(DNS_CACHE_TTL).cacheNegativeTimeToLive(DNS_FAILURE_CACHE_TTL);
    }

    @Bean
    RouteLocator bankRoutes(RouteLocatorBuilder builder) {
        // lb:// разрешает единственный зарегистрированный экземпляр; реплики в этом стенде не
        // запускаются.
        return builder.routes()
                .route("accounts", route -> route.path("/api/accounts/**").uri("lb://accounts"))
                .route("cash", route -> route.path("/api/cash", "/api/cash/**").uri("lb://cash"))
                .route(
                        "transfer",
                        route ->
                                route.path("/api/transfers", "/api/transfers/**")
                                        .uri("lb://transfer"))
                .route(
                        "analytics",
                        route ->
                                route.path("/api/analytics", "/api/analytics/**")
                                        .uri("lb://analytics"))
                .build();
    }
}
