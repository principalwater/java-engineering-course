package dev.principalwater.store.model;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("cart_items")
public record CartLine(@Id Long id, UUID cartId, long productId, int quantity) {
    public CartLine(UUID cartId, long productId, int quantity) {
        this(null, cartId, productId, quantity);
    }

    public CartLine withQuantity(int value) {
        return new CartLine(id, cartId, productId, value);
    }
}
