package dev.principalwater.store.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "shopping_carts")
public class ShoppingCart {
    @Id
    private UUID id;
    @Version
    private Long version;

    protected ShoppingCart() {
    }

    public ShoppingCart(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
}
