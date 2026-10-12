package dev.principalwater.store.controller;

import dev.principalwater.store.service.OrderService;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders")
    public String orders(@RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId, Model model) {
        model.addAttribute("orders", orders.list(cartId));
        return "orders";
    }

    @GetMapping("/orders/{id}")
    public String order(@PathVariable @Positive long id,
                        @RequestParam(defaultValue = "false") boolean newOrder,
                        @RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId, Model model) {
        model.addAttribute("order", orders.get(id, cartId));
        model.addAttribute("newOrder", newOrder);
        return "order";
    }
}
