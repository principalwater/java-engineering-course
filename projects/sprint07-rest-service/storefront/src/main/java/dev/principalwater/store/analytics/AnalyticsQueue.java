package dev.principalwater.store.analytics;

import dev.principalwater.store.dao.OrderLineRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.dao.StoreIdentityRepository;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreIdentity;
import dev.principalwater.store.util.VisitorIdentity;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
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
            return sourceId().flatMap(source -> loadOrders(source, ids));
        });
    }

    private Mono<DeliveryBatch> loadOrders(UUID source, List<Long> ids) {
        return orders.findByIdIn(ids).collectMap(PurchaseOrder::getId, Function.identity())
                .flatMap(selected -> resolveOwners(selected.values())
                        .flatMap(owners -> loadFacts(source, selected, owners)));
    }

    private Mono<Map<UUID, UUID>> resolveOwners(Collection<PurchaseOrder> selected) {
        var cartIds = selected.stream().map(PurchaseOrder::cartId).distinct().toList();
        return database.sql("SELECT cart_id, id FROM store_users WHERE cart_id IN (:carts)")
                .bind("carts", cartIds)
                .map(row -> Map.entry(row.get("cart_id", UUID.class), row.get("id", UUID.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(owners -> {
                    cartIds.forEach(cartId -> owners.computeIfAbsent(cartId, VisitorIdentity::analyticsId));
                    return owners;
                });
    }

    private Mono<DeliveryBatch> loadFacts(UUID source, Map<Long, PurchaseOrder> selected, Map<UUID, UUID> owners) {
        return lines.findByOrderIdInOrderByOrderIdAscLineNumberAsc(selected.keySet())
                .map(line -> {
                    var order = selected.get(line.orderId());
                    return AnalyticsFact.from(source, owners.get(order.cartId()), order, line);
                }).collectList().map(facts -> new DeliveryBatch(List.copyOf(selected.keySet()), facts));
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
                WHERE o.cart_id = :cartId AND o.status = 'PAID'
                """).bind("cartId", cartId).map((row, metadata) -> status(row)).one();
    }

    public Mono<QueueStatus> statusForSeller() {
        return database.sql("""
                SELECT COALESCE(SUM(CASE WHEN a.delivered_at IS NULL THEN 1 ELSE 0 END), 0) AS pending,
                       MAX(a.delivered_at) AS delivered,
                       MIN(CASE WHEN a.delivered_at IS NULL THEN o.created_at END) AS oldest
                FROM analytics_outbox a JOIN purchase_orders o ON o.id = a.order_id
                WHERE o.status = 'PAID'
                """).map((row, metadata) -> status(row)).one();
    }

    private QueueStatus status(Row row) {
        var delivered = row.get("delivered", OffsetDateTime.class);
        var oldest = row.get("oldest", OffsetDateTime.class);
        return new QueueStatus(((Number) row.get("pending")).longValue(),
                delivered == null ? null : delivered.toInstant(), oldest == null ? null : oldest.toInstant());
    }

    @Transactional
    public Mono<Long> acknowledge(DeliveryBatch batch, Instant time) {
        return batch.orderIds().isEmpty() ? Mono.just(0L) : outbox.acknowledge(batch.orderIds(), time);
    }
}
