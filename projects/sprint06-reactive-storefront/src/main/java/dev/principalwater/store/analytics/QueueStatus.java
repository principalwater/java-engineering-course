package dev.principalwater.store.analytics;

import java.time.Instant;

public record QueueStatus(long pendingOrders, Instant lastDelivery) {
}
