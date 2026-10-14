package dev.principalwater.store.controller;

import dev.principalwater.store.util.StoreRedirects;
import dev.principalwater.store.model.CartMutationInput;
import dev.principalwater.store.model.CartMutationResult;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CheckoutService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.reactive.result.view.RedirectView;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
public class CartController {
    private final CartService carts;
    private final CheckoutService checkout;

    public CartController(CartService carts, CheckoutService checkout) {
        this.carts = carts;
        this.checkout = checkout;
    }

    @GetMapping({"/cart", "/cart/items"})
    public String cart() {
        return "cart";
    }

    @PostMapping("/cart/items")
    public Mono<ResponseEntity<?>> change(@Valid @ModelAttribute CartMutationInput input,
                                         @RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId,
                                         ServerWebExchange exchange) {
        boolean json = exchange.getRequest().getHeaders().getAccept().stream()
                .anyMatch(type -> MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type));
        return carts.change(cartId, input.id(), input.action(), input.quantity()).map(cart -> json
                ? ResponseEntity.ok(CartMutationResult.from(input.id(), cart))
                : ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/cart/items")).build());
    }

    @PostMapping("/buy")
    public Mono<RedirectView> buy(@RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId) {
        return checkout.buy(cartId).map(id -> StoreRedirects.to("/orders/" + id + "?newOrder=true"));
    }
}
