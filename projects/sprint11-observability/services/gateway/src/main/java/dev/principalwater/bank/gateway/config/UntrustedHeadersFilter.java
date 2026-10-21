package dev.principalwater.bank.gateway.config;

import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

@Configuration(proxyBeanMethods = false)
public class UntrustedHeadersFilter {
    private static final Logger LOG = LoggerFactory.getLogger(UntrustedHeadersFilter.class);
    private static final Set<String> IDENTITY_HEADERS =
            Set.of("x-owner-id", "x-actor", "x-user-token", "x-account-id");
    private static final Set<String> FORWARDED_HEADERS =
            Set.of("forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto");

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
        boolean identityRemoved =
                headers.keySet()
                        .removeIf(name -> IDENTITY_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
        headers.keySet()
                .removeIf(name -> FORWARDED_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
        if (identityRemoved) {
            LOG.warn("Untrusted identity headers removed");
        }
    }
}
