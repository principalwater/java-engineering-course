package dev.principalwater.study.monitoring;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
class OrderController {
    private final OrderService orders;

    OrderController(OrderService orders) { this.orders = orders; }

    @GetMapping
    List<Order> findAll() { return orders.findAll(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Order save(@RequestBody @Valid NewOrder request) {
        return orders.save(request.username(), request.orderType());
    }

    record NewOrder(@NotBlank @Size(max = Order.USERNAME_MAX_LENGTH) String username,
                    @NotNull OrderType orderType) {}
}
