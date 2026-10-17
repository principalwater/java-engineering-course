package dev.principalwater.study.cloud.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

@Configuration
class GatewayConfiguration {
    private static final String PREFIX = "/property-printer";

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes().route("printers", route -> route
                .path(PREFIX + "/texts", PREFIX + "/actuator/busrefresh", PREFIX + "/actuator/busshutdown")
                .filters(filter -> filter.stripPrefix(1))
                .uri("lb://property-printer-service")).build();
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                // Нестандартный заголовок закрывает browser form CSRF; CORS здесь не включён.
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(exchange ->
                        "bus".equals(exchange.getRequest().getHeaders().getFirst("X-Lab-Operation"))
                                ? ServerWebExchangeMatcher.MatchResult.notMatch()
                                : (java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE)
                                        .contains(exchange.getRequest().getMethod())
                                        ? ServerWebExchangeMatcher.MatchResult.notMatch()
                                        : ServerWebExchangeMatcher.MatchResult.match())))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.GET, PREFIX + "/texts", "/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.POST, PREFIX + "/actuator/busrefresh", PREFIX + "/actuator/busshutdown").authenticated()
                        .anyExchange().denyAll())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
