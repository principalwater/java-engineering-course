package dev.principalwater.store.model;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

// email допускает NULL: аккаунты до восстановления доступа не имеют контакта; authVersion — эпоха учётных данных сессий.
@Table("store_users")
public record StoreUser(@Id UUID id, UUID cartId, String username, String passwordHash,
                        String displayName, AccountRole role, String email, long authVersion, @Version Long version) {
    public StoreUser(UUID id, UUID cartId, String username, String passwordHash, String displayName, String email) {
        this(id, cartId, username, passwordHash, displayName, AccountRole.BUYER, email);
    }

    public StoreUser(UUID id, UUID cartId, String username, String passwordHash, String displayName,
                     AccountRole role, String email) {
        this(id, cartId, username, passwordHash, displayName, role, email, 0, null);
    }

    @Override
    public String toString() {
        return "StoreUser[id=" + id + ", username=" + username + "]";
    }
}
