package dev.principalwater.store.dao;

import dev.principalwater.store.model.PurchaseOrder;
import java.util.Collection;
import java.time.Instant;
import org.springframework.data.r2dbc.repository.Query;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderRepository extends ReactiveCrudRepository<PurchaseOrder, Long> {
    @Query("SELECT * FROM purchase_orders WHERE cart_id = :cartId ORDER BY created_at DESC, id DESC LIMIT :limit")
    Flux<PurchaseOrder> history(UUID cartId, int limit);

    // Кортеж повторяет порядок индекса orders_cart_time_idx (cart_id, created_at DESC, id DESC).
    @Query("""
            SELECT * FROM purchase_orders WHERE cart_id = :cartId
              AND (created_at, id) < (:createdAt, :id)
            ORDER BY created_at DESC, id DESC LIMIT :limit
            """)
    Flux<PurchaseOrder> historyBefore(UUID cartId, Instant createdAt, long id, int limit);

    Mono<PurchaseOrder> findByIdAndCartId(long id, UUID cartId);
    Flux<PurchaseOrder> findByIdIn(Collection<Long> ids);
}
