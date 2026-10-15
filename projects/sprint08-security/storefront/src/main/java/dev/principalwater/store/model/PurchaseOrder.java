package dev.principalwater.store.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("purchase_orders")
public record PurchaseOrder(@Id Long id, UUID eventId, UUID cartId, Instant createdAt, long totalSum,
                            OrderStatus status, UUID paymentId) {
    public PurchaseOrder(UUID cartId, List<CartItem> items, Instant createdAt) {
        this(null, UUID.randomUUID(), cartId, createdAt, total(items), OrderStatus.LEGACY_UNPAID, null);
    }
    public static long total(List<CartItem> items) {
        if (items.isEmpty()) { throw new StoreException(StoreError.EMPTY_CART); }
        return items.stream().mapToLong(item -> Math.multiplyExact(item.product().price(), item.quantity()))
                .reduce(0, Math::addExact);
    }
    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public Instant getCreatedAt() { return createdAt; }
    public long getTotalSum() { return totalSum; }
}
