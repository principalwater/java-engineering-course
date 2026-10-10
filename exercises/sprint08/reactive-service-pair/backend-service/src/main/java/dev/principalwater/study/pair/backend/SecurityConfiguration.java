package dev.principalwater.study.pair.backend;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

@Configuration
@EnableReactiveMethodSecurity
public class SecurityConfiguration {
    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::authorities);
        return http
                // API принимает явно заданный Bearer, без cookie/Basic/form authentication.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // Saved-request cache тоже использует WebSession и может удалить cookie соседнего сервиса.
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(auth -> auth.anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        new ReactiveJwtAuthenticationConverterAdapter(converter))))
                .build();
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        // SERVICE создана как realm-role; dotted claim name не извлекает вложенные JSON-поля.
        if (!(jwt.getClaim(REALM_ACCESS) instanceof Map<?, ?> realm)
                || !(realm.get(ROLES) instanceof List<?> roles)
                || roles.stream().anyMatch(value -> !(value instanceof String role) || role.isBlank())) {
            return List.of();
        }
        return roles.stream().map(value -> (GrantedAuthority) new SimpleGrantedAuthority((String) value)).toList();
    }
}
