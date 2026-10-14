package dev.principalwater.store.controller;

import dev.principalwater.store.service.OrderService;
import dev.principalwater.store.payment.OrderPaymentView;
import org.springframework.web.bind.annotation.ResponseBody;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

@Controller
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders")
    public Mono<String> orders(@RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId, Model model) {
        return orders.list(cartId).map(selected -> {
            model.addAttribute("orders", selected);
            return "orders";
        });
    }

    @GetMapping("/orders/{id}/payment")
    @ResponseBody
    public Mono<OrderPaymentView> payment(@PathVariable @Positive long id,
                                          @RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId) {
        return orders.payment(id, cartId);
    }

    @GetMapping("/orders/{id}")
    public Mono<String> order(@PathVariable @Positive long id,
                              @RequestParam(defaultValue = "false") boolean newOrder,
                              @RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId, Model model) {
        return orders.get(id, cartId).map(selected -> {
            model.addAttribute("order", selected);
            model.addAttribute("newOrder", newOrder);
            return "order";
        });
    }
}
