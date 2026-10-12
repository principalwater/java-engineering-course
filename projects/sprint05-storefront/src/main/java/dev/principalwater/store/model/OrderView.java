package dev.principalwater.store.model;

import java.time.Instant;
import java.util.List;

public record OrderView(long id, Instant createdAt, List<ItemView> items, long totalSum) {
    public OrderView {
        items = List.copyOf(items);
    }

    public static OrderView from(PurchaseOrder order) {
        List<ItemView> items = order.getLines().stream().map(line -> new ItemView(line.getProductId(),
                line.getSku(), line.getTitle(), "", line.getImage().getPath(), line.getPrice(),
                line.getQuantity())).toList();
        return new OrderView(order.getId(), order.getCreatedAt(), items, order.getTotalSum());
    }
}
