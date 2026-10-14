package dev.principalwater.store.analytics;

import dev.principalwater.store.dao.OrderLineRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.dao.StoreIdentityRepository;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreIdentity;
import dev.principalwater.store.util.VisitorIdentity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class AnalyticsQueue {
    private static final int MAX_BATCH_ORDERS = 100;
    private final DatabaseClient database;
    private final OutboxRepository outbox;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final StoreIdentityRepository identity;

    public AnalyticsQueue(OutboxRepository outbox, OrderRepository orders, OrderLineRepository lines,
                          StoreIdentityRepository identity, DatabaseClient database) {
        this.database = database;
        this.outbox = outbox;
        this.orders = orders;
        this.lines = lines;
        this.identity = identity;
    }

    @Transactional(readOnly = true)
    public Mono<DeliveryBatch> capture() {
        return outbox.pendingOrderIds(MAX_BATCH_ORDERS).collectList().flatMap(ids -> {
            if (ids.isEmpty()) {
                return Mono.just(new DeliveryBatch(List.of(), List.of()));
            }
            return sourceId().flatMap(source -> orders.findByIdIn(ids)
                    .collectMap(PurchaseOrder::getId, Function.identity()).flatMap(selected ->
                            database.sql("SELECT cart_id, id FROM store_users WHERE cart_id IN (:carts)")
                                    .bind("carts", selected.values().stream().map(PurchaseOrder::cartId).distinct().toList())
                                    .map(row -> java.util.Map.entry(row.get("cart_id", UUID.class), row.get("id", UUID.class)))
                                    .all().collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)
                                    .flatMap(users -> lines.findByOrderIdInOrderByOrderIdAscLineNumberAsc(ids)
                                            .collectList().map(items -> {
                                var facts = items.stream().map(line -> AnalyticsFact.from(source,
                                        users.getOrDefault(selected.get(line.orderId()).cartId(), VisitorIdentity.analyticsId(selected.get(line.orderId()).cartId())),
                                        selected.get(line.orderId()), line)).toList();
                                return new DeliveryBatch(List.copyOf(selected.keySet()), facts);
                            }))));
        });
    }

    public Mono<UUID> sourceId() {
        return identity.findById(StoreIdentity.SINGLETON_ID)
                .switchIfEmpty(Mono.error(new IllegalStateException("Store dataset identity is missing")))
                .map(StoreIdentity::getSourceId);
    }

    public Mono<QueueStatus> status(UUID cartId) {
        // Состояние доставки тоже изолировано владельцем: очередь чужих покупок не попадает в кабинет.
        return database.sql("""
                SELECT COALESCE(SUM(CASE WHEN a.delivered_at IS NULL THEN 1 ELSE 0 END), 0) AS pending,
                       MAX(a.delivered_at) AS delivered,
                       MIN(CASE WHEN a.delivered_at IS NULL THEN o.created_at END) AS oldest
                FROM analytics_outbox a JOIN purchase_orders o ON o.id = a.order_id
                WHERE o.cart_id = :cartId
                """).bind("cartId", cartId).map(row -> {
                    var delivered = row.get("delivered", OffsetDateTime.class);
                    var oldest = row.get("oldest", OffsetDateTime.class);
                    return new QueueStatus(((Number) row.get("pending")).longValue(),
                            delivered == null ? null : delivered.toInstant(), oldest == null ? null : oldest.toInstant());
                }).one();
    }

    @Transactional
    public Mono<Long> acknowledge(DeliveryBatch batch, Instant time) {
        return batch.orderIds().isEmpty() ? Mono.just(0L) : outbox.acknowledge(batch.orderIds(), time);
    }
}
