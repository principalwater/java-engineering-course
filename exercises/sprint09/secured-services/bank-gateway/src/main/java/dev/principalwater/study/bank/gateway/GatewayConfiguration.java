package dev.principalwater.study.bank.gateway;

import dev.principalwater.study.bank.identity.RealmAuthorities;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
class GatewayConfiguration {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, JwtTokenRelayGatewayFilterFactory relay) {
        return builder.routes().route("transfers", route -> route.path("/api/transfers", "/api/transfers/**")
                .filters(filter -> filter.stripPrefix(1).filter(relay.apply(new Object())))
                .uri("http://127.0.0.1:19932")).build();
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmAuthorities());
        return http.csrf(csrf -> csrf.disable())
                .securityContextRepository(org.springframework.security.web.server.context.NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/api/transfers", "/api/transfers/**").hasRole("USER").anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        new ReactiveJwtAuthenticationConverterAdapter(converter)))).build();
    }
}
