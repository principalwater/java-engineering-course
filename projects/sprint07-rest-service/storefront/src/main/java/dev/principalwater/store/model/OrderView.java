package dev.principalwater.store.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderView(long id, Instant createdAt, List<OrderLineView> items, long totalSum,
                        OrderStatus status, UUID paymentId) {
    public OrderView { items = List.copyOf(items); }
    public static OrderView from(PurchaseOrder order, List<OrderLine> lines) {
        return new OrderView(order.id(), order.createdAt(), lines.stream().map(OrderLineView::from).toList(),
                order.totalSum(), order.status(), order.paymentId());
    }
}
