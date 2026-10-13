package dev.principalwater.store.model;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("store_users")
public record StoreUser(@Id UUID id, UUID cartId, String username, String passwordHash,
                        String displayName, @Version Long version) {
    public StoreUser(UUID id, UUID cartId, String username, String passwordHash, String displayName) {
        this(id, cartId, username, passwordHash, displayName, null);
    }

    @Override
    public String toString() {
        return "StoreUser[id=" + id + ", username=" + username + "]";
    }
}
