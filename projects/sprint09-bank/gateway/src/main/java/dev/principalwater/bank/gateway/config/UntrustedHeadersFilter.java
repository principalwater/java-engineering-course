package dev.principalwater.bank.gateway.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        return (exchange, chain) ->
                chain.filter(
                        exchange.mutate()
                                .request(
                                        request ->
                                                request.headers(
                                                        headers ->
                                                                headers.keySet()
                                                                        .removeIf(
                                                                                name ->
                                                                                        REMOVED
                                                                                                .contains(
                                                                                                        name
                                                                                                                .toLowerCase(
                                                                                                                        Locale
                                                                                                                                .ROOT)))))
                                .build());
    }
}
