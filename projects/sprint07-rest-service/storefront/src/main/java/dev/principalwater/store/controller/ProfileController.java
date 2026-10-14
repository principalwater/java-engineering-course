package dev.principalwater.store.controller;

import dev.principalwater.store.service.OrderService;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import reactor.core.publisher.Mono;

@Controller
public class ProfileController {
    private final OrderService orders;

    public ProfileController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/profile")
    public Mono<String> profile(@RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId, Model model) {
        return orders.profile(cartId).map(view -> {
            model.addAttribute("profile", view);
            return "profile";
        });
    }
}
