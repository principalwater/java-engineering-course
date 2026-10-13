package dev.principalwater.store.model;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("shopping_carts")
public record ShoppingCart(@Id UUID id, @Version Long version) {
    public ShoppingCart(UUID id) {
        this(id, null);
    }

    public UUID getId() { return id; }
}
