package dev.principalwater.store.payment;

import dev.principalwater.store.model.CartItem;
import dev.principalwater.store.model.PurchaseOrder;
import java.util.List;
import java.util.UUID;

public record CheckoutSnapshot(UUID key, List<CartItem> items) {
    public CheckoutSnapshot { items = List.copyOf(items); }
    public long totalRubles() { return items.isEmpty() ? 0 : PurchaseOrder.total(items); }
}
