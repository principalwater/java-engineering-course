package dev.principalwater.store.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("analytics_outbox")
public record AnalyticsOutbox(@Id Long id, long orderId, Instant deliveredAt) {
    public AnalyticsOutbox(long orderId) {
        this(null, orderId, null);
    }
}
