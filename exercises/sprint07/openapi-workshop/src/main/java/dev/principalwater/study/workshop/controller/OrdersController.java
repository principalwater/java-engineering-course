package dev.principalwater.study.workshop.controller;

import dev.principalwater.study.workshop.generated.api.OrdersApi;
import dev.principalwater.study.workshop.generated.model.Order;
import dev.principalwater.study.workshop.generated.model.OrderInput;
import dev.principalwater.study.workshop.service.WorkshopService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class OrdersController implements OrdersApi {
    private final WorkshopService service;

    public OrdersController(WorkshopService service) {
        this.service = service;
    }

    @Override
    public Mono<ResponseEntity<Flux<Order>>> listOrders(ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(Flux.fromIterable(service.orders())));
    }

    @Override
    public Mono<ResponseEntity<Order>> getOrder(Long id, ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(service.order(id)));
    }

    @Override
    public Mono<ResponseEntity<Order>> createOrder(Mono<OrderInput> input, ServerWebExchange exchange) {
        return input.map(service::createOrder)
                .map(order -> ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(order));
    }

    @Override
    public Mono<ResponseEntity<Order>> updateOrder(Long id, Mono<OrderInput> input, ServerWebExchange exchange) {
        return input.map(order -> ResponseEntity.ok(service.updateOrder(id, order)));
    }

    @Override
    public Mono<ResponseEntity<Void>> deleteOrder(Long id, ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            service.deleteOrder(id);
            return ResponseEntity.noContent().build();
        });
    }
}
