package dev.principalwater.store.controller;

import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.StoreLimits;
import dev.principalwater.store.model.StoreUserDetails;
import dev.principalwater.store.service.CartService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@ControllerAdvice(basePackages = "dev.principalwater.store.controller")
public class CommonViewModel {
    private final CartService carts;

    public CommonViewModel(CartService carts) {
        this.carts = carts;
    }

    @ModelAttribute("cart")
    public Mono<CartView> cart(ServerWebExchange exchange) {
        UUID cartId = exchange.getAttribute(CartIdentityFilter.ATTRIBUTE_NAME);
        return cartId == null ? Mono.just(new CartView(List.of())) : carts.view(cartId);
    }

    @ModelAttribute("account")
    public Mono<StoreUserDetails> account(ServerWebExchange exchange) {
        // Null с ServerWebExchange завершает invocation без HandlerResult и отменяет zip остальных атрибутов.
        StoreUserDetails account = exchange.getAttribute(CartIdentityFilter.ACCOUNT_ATTRIBUTE_NAME);
        return Mono.justOrEmpty(account);
    }

    @ModelAttribute("_csrf")
    public Mono<CsrfToken> csrf(ServerWebExchange exchange) {
        if (exchange.getAttribute(CartIdentityFilter.ACCOUNT_ATTRIBUTE_NAME) == null
                && !exchange.getRequest().getPath().value().startsWith("/account/")) {
            return Mono.empty();
        }
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        return token == null ? Mono.empty() : token;
    }

    @ModelAttribute("pageSizes")
    public List<Integer> pageSizes() {
        return StoreLimits.PAGE_SIZES;
    }

    @ModelAttribute("maxQuantity")
    public int maxQuantity() {
        return StoreLimits.MAX_ITEM_QUANTITY;
    }

    @ModelAttribute("notice")
    public Mono<String> notice(ServerWebExchange exchange) {
        // Redirect тоже разрешает реактивную модель: POST не должен забрать уведомление до следующего GET.
        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return Mono.empty();
        }
        // ReactiveRedisSessionRepository сохраняет delta при смене id: завершаем CSRF-rotation до изменения уведомления.
        return csrf(exchange).then(exchange.getSession()).flatMap(session -> {
            String message = session.getAttribute("notice");
            if (message != null) {
                session.getAttributes().remove("notice");
            }
            return Mono.justOrEmpty(message);
        });
    }
}
