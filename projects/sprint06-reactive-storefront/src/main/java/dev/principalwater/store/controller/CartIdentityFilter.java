package dev.principalwater.store.controller;

import dev.principalwater.store.config.CartProperties;
import dev.principalwater.store.util.VisitorIdentity;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.model.StoreUserDetails;
import org.springframework.security.core.Authentication;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
// Проверка CSRF предшествует созданию корзины: отклонённый POST не пишет в БД.
@Order(-50)
public class CartIdentityFilter implements WebFilter {
    public static final String ATTRIBUTE_NAME = "cartId";
    public static final String USER_ATTRIBUTE_NAME = "analyticsUserId";
    public static final String ACCOUNT_ATTRIBUTE_NAME = "account";
    private static final int UUID_TEXT_LENGTH = 36;
    private static final Duration COOKIE_LIFETIME = Duration.ofDays(30);
    private static final Set<String> ROOTS = Set.of("/items", "/cart", "/orders", "/buy", "/catalog", "/analytics", "/profile", "/account");
    private final CartService carts;
    private final CartProperties properties;

    public CartIdentityFilter(CartService carts, CartProperties properties) {
        this.carts = carts;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.equals("/") && ROOTS.stream().noneMatch(root -> path.equals(root) || path.startsWith(root + "/"))) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal().ofType(Authentication.class).filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal).ofType(StoreUserDetails.class).map(account -> {
                    exchange.getAttributes().put(ACCOUNT_ATTRIBUTE_NAME, account);
                    exchange.getAttributes().put(USER_ATTRIBUTE_NAME, account.accountId());
                    return account.cartId();
                }).switchIfEmpty(Mono.defer(() -> guestCart(exchange))).flatMap(id -> {
                    exchange.getAttributes().put(ATTRIBUTE_NAME, id);
                    return chain.filter(exchange);
                });
    }

    private Mono<UUID> guestCart(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(properties.cookieName());
        UUID previous = cookie == null ? null : parse(cookie.getValue());
        return carts.identify(previous).doOnNext(id -> {
            exchange.getAttributes().put(USER_ATTRIBUTE_NAME, VisitorIdentity.analyticsId(id));
            if (!id.equals(previous)) {
                // В cookie хранится только гостевая корзина, включая время входа и выхода из аккаунта.
                exchange.getResponse().addCookie(ResponseCookie.from(properties.cookieName(), id.toString())
                        .path("/").httpOnly(true).secure("https".equals(exchange.getRequest().getURI().getScheme()))
                        .sameSite("Lax").maxAge(COOKIE_LIFETIME).build());
            }
        });
    }

    private UUID parse(String value) {
        if (value == null || value.length() != UUID_TEXT_LENGTH) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
