package dev.principalwater.store.controller;

import dev.principalwater.store.model.StoreUserDetails;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class CartIdentityFilter implements WebFilter, Ordered {
    public static final String ATTRIBUTE_NAME = "cartId";
    public static final String USER_ATTRIBUTE_NAME = "analyticsUserId";
    public static final String ACCOUNT_ATTRIBUTE_NAME = "account";
    // Цепочка Spring Security (WebFilterChainProxy) стоит на order -100, но её константа закрыта в package-private
    // WebFluxSecurityConfiguration. Фильтр идёт с запасом 50 после неё: exchange.getPrincipal() уже содержит результат
    // аутентификации, поэтому cookie или параметры запроса не выбирают владельца данных.
    private static final int SECURITY_CHAIN_ORDER = -100;
    private static final int ORDER = SECURITY_CHAIN_ORDER + 50;

    @Override
    public int getOrder() { return ORDER; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal().ofType(Authentication.class).filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal).ofType(StoreUserDetails.class).doOnNext(account -> {
                    exchange.getAttributes().put(ACCOUNT_ATTRIBUTE_NAME, account);
                    exchange.getAttributes().put(USER_ATTRIBUTE_NAME, account.accountId());
                    exchange.getAttributes().put(ATTRIBUTE_NAME, account.cartId());
                }).then(Mono.defer(() -> chain.filter(exchange)));
    }
}
