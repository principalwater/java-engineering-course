package dev.principalwater.study.workshop.repository;

import dev.principalwater.study.workshop.generated.model.OrderStatus;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

@Repository
public class WorkshopRepository {
    private final ConcurrentHashMap<Long, CustomerRow> customers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, OrderRow> orders = new ConcurrentHashMap<>();
    private final AtomicLong customerIds = new AtomicLong();
    private final AtomicLong orderIds = new AtomicLong();

    public List<CustomerRow> customers() {
        return customers.values().stream().sorted(Comparator.comparingLong(CustomerRow::id)).toList();
    }

    public CustomerRow customer(long id) {
        return customers.get(id);
    }

    public CustomerRow createCustomer(String name, String phone, String email) {
        var row = new CustomerRow(customerIds.incrementAndGet(), name, phone, email);
        customers.put(row.id(), row);
        return row;
    }

    public List<OrderRow> orders() {
        return orders.values().stream().sorted(Comparator.comparingLong(OrderRow::id)).toList();
    }

    public OrderRow order(long id) {
        return orders.get(id);
    }

    public OrderRow createOrder(String carModel, String serviceType, OrderStatus status,
                                BigDecimal price, long customerId) {
        var row = new OrderRow(orderIds.incrementAndGet(), carModel, serviceType, status, price, customerId);
        orders.put(row.id(), row);
        return row;
    }

    public OrderRow updateOrder(long id, String carModel, String serviceType, OrderStatus status,
                                BigDecimal price, long customerId) {
        // Атомарная замена не восстанавливает заказ, который уже удалил параллельный запрос.
        return orders.computeIfPresent(id, (key, previous) ->
                new OrderRow(key, carModel, serviceType, status, price, customerId));
    }

    public boolean deleteOrder(long id) {
        return orders.remove(id) != null;
    }

    public record CustomerRow(long id, String name, String phone, String email) {}

    public record OrderRow(long id, String carModel, String serviceType, OrderStatus status,
                           BigDecimal price, long customerId) {}
}
