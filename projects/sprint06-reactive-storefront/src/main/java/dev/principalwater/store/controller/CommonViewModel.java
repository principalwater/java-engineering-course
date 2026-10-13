package dev.principalwater.store.controller;

import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.StoreLimits;
import dev.principalwater.store.service.CartService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestAttribute;

@ControllerAdvice(basePackages = "dev.principalwater.store.controller")
public class CommonViewModel {
    private final CartService carts;

    public CommonViewModel(CartService carts) {
        this.carts = carts;
    }

    @ModelAttribute("cart")
    public CartView cart(@RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId) {
        return carts.view(cartId);
    }

    @ModelAttribute("pageSizes")
    public List<Integer> pageSizes() {
        return StoreLimits.PAGE_SIZES;
    }

    @ModelAttribute("maxQuantity")
    public int maxQuantity() {
        return StoreLimits.MAX_ITEM_QUANTITY;
    }
}
