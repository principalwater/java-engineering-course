package dev.principalwater.store.controller;

import dev.principalwater.store.service.OrderService;
import dev.principalwater.store.payment.WalletService;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import reactor.core.publisher.Mono;

@Controller
public class ProfileController {
    private final OrderService orders;
    private final WalletService wallets;

    public ProfileController(OrderService orders, WalletService wallets) {
        this.orders = orders;
        this.wallets = wallets;
    }

    @GetMapping("/profile")
    public Mono<String> profile(@RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId,
                                @RequestAttribute(CartIdentityFilter.USER_ATTRIBUTE_NAME) UUID userId, Model model) {
        return Mono.zip(orders.profile(cartId), wallets.balance(userId)).map(view -> {
            model.addAttribute("profile", view.getT1());
            model.addAttribute("wallet", view.getT2());
            return "profile";
        });
    }
}
