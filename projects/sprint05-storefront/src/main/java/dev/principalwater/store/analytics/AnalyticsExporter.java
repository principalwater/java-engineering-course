package dev.principalwater.store.analytics;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "store.analytics.enabled", havingValue = "true")
public class AnalyticsExporter {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsExporter.class);
    private final AnalyticsQueue queue;
    private final ClickHouseClient clickhouse;
    private final Clock clock;
    private boolean failed;

    public AnalyticsExporter(AnalyticsQueue queue, ClickHouseClient clickhouse, Clock clock) {
        this.queue = queue;
        this.clickhouse = clickhouse;
        this.clock = clock;
    }

    public synchronized int flush() {
        var batch = queue.capture();
        if (batch.facts().isEmpty()) {
            return 0;
        }
        // Захват и подтверждение — отдельные короткие транзакции; HTTP не удерживает соединение БД.
        clickhouse.insert(batch.facts());
        queue.acknowledge(batch, clock.instant());
        if (failed) {
            LOG.info("Analytics delivery recovered");
            failed = false;
        }
        return batch.orderIds().size();
    }

    @Scheduled(fixedDelayString = "${store.analytics.flush-delay:PT5S}")
    public synchronized void deliverPendingOrders() {
        try {
            flush();
        } catch (RuntimeException exception) {
            if (!failed) {
                LOG.warn("Analytics delivery failed; pending orders will be retried", exception);
                failed = true;
            }
        }
    }
}
