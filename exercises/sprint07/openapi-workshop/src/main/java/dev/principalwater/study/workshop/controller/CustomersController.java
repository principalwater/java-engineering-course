package dev.principalwater.study.workshop.controller;

import dev.principalwater.study.workshop.generated.api.CustomersApi;
import dev.principalwater.study.workshop.generated.model.Customer;
import dev.principalwater.study.workshop.generated.model.CustomerInput;
import dev.principalwater.study.workshop.service.WorkshopService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class CustomersController implements CustomersApi {
    private final WorkshopService service;

    public CustomersController(WorkshopService service) {
        this.service = service;
    }

    @Override
    public Mono<ResponseEntity<Flux<Customer>>> listCustomers(ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(Flux.fromIterable(service.customers())));
    }

    @Override
    public Mono<ResponseEntity<Customer>> getCustomer(Long id, ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(service.customer(id)));
    }

    @Override
    public Mono<ResponseEntity<Customer>> createCustomer(Mono<CustomerInput> input, ServerWebExchange exchange) {
        return input.map(service::createCustomer)
                .map(customer -> ResponseEntity.created(URI.create("/api/customers/" + customer.getId())).body(customer));
    }
}
