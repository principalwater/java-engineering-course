package dev.principalwater.store.dao;

import dev.principalwater.store.model.AnalyticsOutbox;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OutboxRepository extends ReactiveCrudRepository<AnalyticsOutbox, Long> {
    @Query("SELECT order_id FROM analytics_outbox WHERE delivered_at IS NULL ORDER BY id LIMIT :limit")
    Flux<Long> pendingOrderIds(int limit);

    Mono<Long> countByDeliveredAtIsNull();

    @Query("SELECT delivered_at FROM analytics_outbox WHERE delivered_at IS NOT NULL ORDER BY delivered_at DESC LIMIT 1")
    // Скаляр TIMESTAMP WITH TIME ZONE читается нативным типом драйвера, затем переводится в Instant.
    Mono<OffsetDateTime> latestDelivery();

    @Query("SELECT o.created_at FROM analytics_outbox q JOIN purchase_orders o ON o.id = q.order_id WHERE q.delivered_at IS NULL ORDER BY o.created_at LIMIT 1")
    Mono<OffsetDateTime> oldestPending();

    @Modifying
    @Query("UPDATE analytics_outbox SET delivered_at = :time WHERE order_id IN (:ids) AND delivered_at IS NULL")
    Mono<Long> acknowledge(Collection<Long> ids, Instant time);
}
