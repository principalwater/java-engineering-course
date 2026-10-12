package dev.principalwater.store.analytics;

import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.dao.StoreIdentityRepository;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreIdentity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnalyticsQueue {
    private static final int MAX_BATCH_ORDERS = 100;
    private final OutboxRepository outbox;
    private final OrderRepository orders;
    private final StoreIdentityRepository identity;

    public AnalyticsQueue(OutboxRepository outbox, OrderRepository orders, StoreIdentityRepository identity) {
        this.outbox = outbox;
        this.orders = orders;
        this.identity = identity;
    }

    public DeliveryBatch capture() {
        var ids = outbox.pendingOrderIds(PageRequest.of(0, MAX_BATCH_ORDERS));
        if (ids.isEmpty()) {
            return new DeliveryBatch(List.of(), List.of());
        }
        UUID source = sourceId();
        // Сначала ограничиваем список ID; fetch join коллекции не участвует в пагинации.
        var selected = orders.findByIdIn(ids);
        var facts = selected.stream().flatMap(order -> order.getLines().stream()
                .map(line -> AnalyticsFact.from(source, order, line))).toList();
        return new DeliveryBatch(selected.stream().map(PurchaseOrder::getId).toList(), facts);
    }

    public UUID sourceId() {
        return identity.findById(StoreIdentity.SINGLETON_ID).orElseThrow(() ->
                new IllegalStateException("Store dataset identity is missing")).getSourceId();
    }

    public QueueStatus status() {
        return new QueueStatus(outbox.countByDeliveredAtIsNull(), outbox.latestDelivery().orElse(null));
    }

    @Transactional
    public void acknowledge(DeliveryBatch batch, Instant time) {
        if (!batch.orderIds().isEmpty()) {
            outbox.acknowledge(batch.orderIds(), time);
        }
    }
}
