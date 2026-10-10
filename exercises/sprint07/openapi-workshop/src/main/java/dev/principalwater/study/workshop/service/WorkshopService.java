package dev.principalwater.study.workshop.service;

import dev.principalwater.study.workshop.generated.model.Customer;
import dev.principalwater.study.workshop.generated.model.CustomerInput;
import dev.principalwater.study.workshop.generated.model.Order;
import dev.principalwater.study.workshop.generated.model.OrderInput;
import dev.principalwater.study.workshop.repository.WorkshopRepository;
import dev.principalwater.study.workshop.repository.WorkshopRepository.CustomerRow;
import dev.principalwater.study.workshop.repository.WorkshopRepository.OrderRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkshopService {
    private final WorkshopRepository repository;

    public WorkshopService(WorkshopRepository repository) {
        this.repository = repository;
    }

    public List<Customer> customers() {
        return repository.customers().stream().map(WorkshopService::customerDto).toList();
    }

    public Customer customer(long id) {
        var row = repository.customer(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        return customerDto(row);
    }

    public Customer createCustomer(CustomerInput input) {
        requireText(input.getName());
        return customerDto(repository.createCustomer(input.getName(), input.getPhone(), input.getEmail()));
    }

    public List<Order> orders() {
        return repository.orders().stream().map(WorkshopService::orderDto).toList();
    }

    public Order order(long id) {
        return orderDto(repository.order(id));
    }

    public Order createOrder(OrderInput input) {
        validateOrder(input);
        return orderDto(repository.createOrder(input.getCarModel(), input.getServiceType(),
                input.getStatus(), input.getPrice(), input.getCustomerId()));
    }

    public Order updateOrder(long id, OrderInput input) {
        validateOrder(input);
        return orderDto(repository.updateOrder(id, input.getCarModel(), input.getServiceType(),
                input.getStatus(), input.getPrice(), input.getCustomerId()));
    }

    public void deleteOrder(long id) {
        if (!repository.deleteOrder(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }

    private void validateOrder(OrderInput input) {
        requireText(input.getCarModel());
        requireText(input.getServiceType());
        customer(input.getCustomerId());
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text must not be blank");
        }
    }

    private static Customer customerDto(CustomerRow row) {
        return new Customer(row.id(), row.name(), row.phone(), row.email());
    }

    private static Order orderDto(OrderRow row) {
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return new Order(row.id(), row.carModel(), row.serviceType(), row.status(), row.price(), row.customerId());
    }
}
