package dev.principalwater.store.model;

import java.time.Instant;
import java.util.List;

public record OrderView(long id, Instant createdAt, List<OrderLineView> items, long totalSum) {
    public OrderView {
        items = List.copyOf(items);
    }

    public static OrderView from(PurchaseOrder order) {
        List<OrderLineView> items = order.getLines().stream().map(OrderLineView::from).toList();
        return new OrderView(order.getId(), order.getCreatedAt(), items, order.getTotalSum());
    }
}
