package dev.principalwater.store.analytics;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "store.analytics.enabled", havingValue = "true")
public class AnalyticsDeliveryCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsDeliveryCoordinator.class);
    private final AnalyticsExporter exporter;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();

    public AnalyticsDeliveryCoordinator(AnalyticsExporter exporter) {
        this.exporter = exporter;
    }

    public Mono<DeliveryResult> flush() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) {
                return Mono.just(DeliveryResult.inProgress());
            }
            // shortcut: допуск защищает одну JVM; для нескольких экспортёров нужен долговечный lease очереди.
            return Mono.defer(exporter::flush).map(DeliveryResult::delivered).doOnNext(result -> {
                if (result.deliveredOrders() > 0 && failed.getAndSet(false)) {
                    LOG.info("Analytics delivery recovered");
                }
            }).doFinally(signal -> running.set(false));
        });
    }

    @Scheduled(fixedDelayString = "${store.analytics.flush-delay:PT5S}")
    public Mono<Void> deliverPendingOrders() {
        // Spring повторно подписывается на отложенный publisher; следующая попытка ждёт его завершения.
        return flush().onErrorResume(exception -> {
            if (failed.compareAndSet(false, true)) {
                LOG.warn("Analytics delivery failed; pending orders will be retried", exception);
            }
            return Mono.empty();
        }).then();
    }
}
