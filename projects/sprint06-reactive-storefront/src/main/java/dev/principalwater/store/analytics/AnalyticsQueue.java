package dev.principalwater.store.analytics;

import dev.principalwater.store.dao.OrderLineRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.dao.StoreIdentityRepository;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreIdentity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class AnalyticsQueue {
    private static final int MAX_BATCH_ORDERS = 100;
    private final OutboxRepository outbox;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final StoreIdentityRepository identity;

    public AnalyticsQueue(OutboxRepository outbox, OrderRepository orders, OrderLineRepository lines,
                          StoreIdentityRepository identity) {
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
                            lines.findByOrderIdInOrderByOrderIdAscLineNumberAsc(ids).collectList().map(items -> {
                                var facts = items.stream().map(line -> AnalyticsFact.from(source,
                                        selected.get(line.orderId()), line)).toList();
                                return new DeliveryBatch(List.copyOf(selected.keySet()), facts);
                            })));
        });
    }

    public Mono<UUID> sourceId() {
        return identity.findById(StoreIdentity.SINGLETON_ID)
                .switchIfEmpty(Mono.error(new IllegalStateException("Store dataset identity is missing")))
                .map(StoreIdentity::getSourceId);
    }

    public Mono<QueueStatus> status() {
        // Метаданные очереди не открывают общую транзакцию: независимые чтения используют свои соединения.
        return Mono.zip(outbox.countByDeliveredAtIsNull(),
                        outbox.latestDelivery().map(OffsetDateTime::toInstant).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        outbox.oldestPending().map(OffsetDateTime::toInstant).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .map(values -> new QueueStatus(values.getT1(), values.getT2().orElse(null), values.getT3().orElse(null)));
    }

    @Transactional
    public Mono<Long> acknowledge(DeliveryBatch batch, Instant time) {
        return batch.orderIds().isEmpty() ? Mono.just(0L) : outbox.acknowledge(batch.orderIds(), time);
    }
}
