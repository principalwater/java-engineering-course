package dev.principalwater.store.analytics;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "store.analytics.enabled", havingValue = "true")
public class AnalyticsExporter {
    private final AnalyticsQueue queue;
    private final ClickHouseClient clickhouse;
    private final Clock clock;

    public AnalyticsExporter(AnalyticsQueue queue, ClickHouseClient clickhouse, Clock clock) {
        this.queue = queue;
        this.clickhouse = clickhouse;
        this.clock = clock;
    }

    int flush() {
        var batch = queue.capture();
        if (batch.facts().isEmpty()) {
            return 0;
        }
        // Захват и подтверждение — отдельные короткие транзакции; HTTP не удерживает соединение БД.
        clickhouse.insert(batch.facts());
        queue.acknowledge(batch, clock.instant());
        return batch.orderIds().size();
    }
}
