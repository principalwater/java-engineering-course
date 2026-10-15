package dev.principalwater.store.dao;

import dev.principalwater.store.payment.CheckoutIntent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CheckoutIntentRepository extends ReactiveCrudRepository<CheckoutIntent, UUID> {
    Mono<CheckoutIntent> findByActiveCartId(UUID cartId);
    Mono<CheckoutIntent> findFirstByCartIdOrderByCreatedAtDesc(UUID cartId);
    @Query("SELECT * FROM checkout_intents WHERE status = 'PREPARED' "
            + "AND COALESCE(last_attempt_at, created_at) <= :retryBefore "
            + "ORDER BY last_attempt_at NULLS FIRST, created_at, id LIMIT :batchSize")
    Flux<CheckoutIntent> pending(Instant retryBefore, int batchSize);
    @Modifying
    @Query("UPDATE checkout_intents SET last_attempt_at = :attemptedAt WHERE id = :id AND status = 'PREPARED' "
            + "AND COALESCE(last_attempt_at, created_at) <= :retryBefore")
    Mono<Integer> markAttempt(UUID id, Instant attemptedAt, Instant retryBefore);
    @Modifying
    @Query("UPDATE checkout_intents SET status = 'CONFIRMED', active_cart_id = NULL, order_id = :orderId, "
            + "version = version + 1 WHERE id = :id AND status = 'PREPARED'")
    Mono<Integer> confirm(UUID id, long orderId);
    @Modifying
    @Query("UPDATE checkout_intents SET status = 'DECLINED', active_cart_id = NULL, version = version + 1 "
            + "WHERE id = :id AND status = 'PREPARED'")
    Mono<Integer> decline(UUID id);
}
