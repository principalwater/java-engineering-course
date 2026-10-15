package dev.principalwater.store.payment;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("checkout_intents")
public record CheckoutIntent(@Id UUID id, UUID cartId, UUID activeCartId, UUID payerId, long amountMinor,
                             Status status, Long orderId, Instant createdAt, @Version Long version) {
    public enum Status { PREPARED, CONFIRMED, DECLINED }
    public CheckoutIntent(UUID key, UUID cartId, UUID payerId, long amountMinor, Instant now) {
        this(key, cartId, cartId, payerId, amountMinor, Status.PREPARED, null, now, null);
    }
}
