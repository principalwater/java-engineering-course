package dev.principalwater.bank.gateway.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

@Configuration(proxyBeanMethods = false)
public class UntrustedHeadersFilter {
    private static final Set<String> REMOVED =
            Set.of(
                    "x-owner-id",
                    "x-actor",
                    "x-user-token",
                    "x-account-id",
                    "forwarded",
                    "x-forwarded-for",
                    "x-forwarded-host",
                    "x-forwarded-proto");

    @Bean
    GlobalFilter stripUntrustedIdentity() {
        // Authorization остаётся исходным user access token; заголовки не выбирают владельца счёта.
        return (exchange, chain) -> {
            ServerHttpRequest request =
                    exchange.getRequest().mutate().headers(this::removeUntrustedHeaders).build();
            return chain.filter(exchange.mutate().request(request).build());
        };
    }

    private void removeUntrustedHeaders(HttpHeaders headers) {
        headers.keySet().removeIf(name -> REMOVED.contains(name.toLowerCase(Locale.ROOT)));
    }
}
