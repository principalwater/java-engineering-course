package dev.principalwater.store.controller;

import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CheckoutService;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

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
    public RedirectView change(@RequestParam @Positive long id, @RequestParam CartAction action,
                               @RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId) {
        carts.change(cartId, id, action);
        return StoreRedirects.to("/cart/items");
    }

    @PostMapping("/buy")
    public RedirectView buy(@RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId) {
        return StoreRedirects.to("/orders/" + checkout.buy(cartId) + "?newOrder=true");
    }
}
