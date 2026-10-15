package dev.principalwater.store.controller;

import dev.principalwater.store.model.StoreUserDetails;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
// Выполняется после security: cookie или параметры запроса не выбирают владельца данных.
@Order(-50)
public class CartIdentityFilter implements WebFilter {
    public static final String ATTRIBUTE_NAME = "cartId";
    public static final String USER_ATTRIBUTE_NAME = "analyticsUserId";
    public static final String ACCOUNT_ATTRIBUTE_NAME = "account";

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
