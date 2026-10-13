package dev.principalwater.store.analytics;

import java.util.List;

public record DeliveryBatch(List<Long> orderIds, List<AnalyticsFact> facts) {
    public DeliveryBatch {
        orderIds = List.copyOf(orderIds);
        facts = List.copyOf(facts);
    }
}
