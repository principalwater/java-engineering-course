package dev.principalwater.bank.gateway.config;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RoutesConfiguration {
    @Bean
    HttpClientCustomizer discoveryDnsCache() {
        // После recreate Docker может отдать старый IP другому сервису; DNS TTL должен быть
        // коротким.
        return client ->
                client.resolver(
                        resolver ->
                                resolver.cacheMaxTimeToLive(Duration.ofSeconds(5))
                                        .cacheNegativeTimeToLive(Duration.ofSeconds(1)));
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
