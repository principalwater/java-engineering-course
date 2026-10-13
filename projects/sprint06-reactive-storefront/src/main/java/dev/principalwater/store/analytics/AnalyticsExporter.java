package dev.principalwater.store.analytics;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    Mono<Integer> flush() {
        return queue.capture().flatMap(batch -> {
            if (batch.facts().isEmpty()) {
                return Mono.just(0);
            }
            // HTTP выполняется между двумя короткими транзакциями; время ACK читается после подтверждения.
            return clickhouse.insert(batch.facts())
                    .then(Mono.defer(() -> queue.acknowledge(batch, clock.instant())))
                    .thenReturn(batch.orderIds().size());
        });
    }
}
