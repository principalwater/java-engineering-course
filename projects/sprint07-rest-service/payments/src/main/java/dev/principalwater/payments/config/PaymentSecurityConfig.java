package dev.principalwater.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.payments.controller.PaymentProblems;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class PaymentSecurityConfig {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SERVICE_IDENTITY = "storefront";
    private static final String AUTHENTICATION_REQUIRED = "Service authentication is required";

    @Bean
    public ReactiveAuthenticationManager paymentAuthentication(PaymentProperties properties) {
        return authentication -> properties.accepts(authentication.getCredentials() instanceof String token ? token : null)
                ? Mono.just(new UsernamePasswordAuthenticationToken(SERVICE_IDENTITY, null,
                        List.of(new SimpleGrantedAuthority("PAYMENTS"))))
                : Mono.error(new BadCredentialsException(AUTHENTICATION_REQUIRED));
    }

    @Bean
    public SecurityWebFilterChain paymentSecurity(ServerHttpSecurity http, ReactiveAuthenticationManager manager, ObjectMapper json) {
        var authentication = new AuthenticationWebFilter(manager);
        authentication.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        authentication.setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers("/api/**"));
        authentication.setServerAuthenticationConverter(exchange -> {
            List<String> values = exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);
            if (values.isEmpty()) {
                return Mono.empty();
            }
            String header = values.getFirst();
            if (values.size() != 1 || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return Mono.error(new BadCredentialsException(AUTHENTICATION_REQUIRED));
            }
            return Mono.just(new UsernamePasswordAuthenticationToken(SERVICE_IDENTITY, header.substring(BEARER_PREFIX.length())));
        });
        authentication.setAuthenticationFailureHandler((web, failure) -> {
            web.getExchange().getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return PaymentProblems.write(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, web.getExchange(), json);
        });
        return http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                // Только явный Bearer header: браузерные cookie/session credentials здесь не принимаются.
                .csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
                .addFilterAt(authentication, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(access -> access.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/api/**").authenticated().anyExchange().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((exchange, failure) -> {
                    exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    return PaymentProblems.write(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, exchange, json);
                }).accessDeniedHandler((exchange, failure) -> PaymentProblems.write(HttpStatus.FORBIDDEN, "Access denied", exchange, json)))
                .build();
    }
}
