package dev.principalwater.store.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "store.analytics.enabled", havingValue = "true")
public class AnalyticsDeliveryCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsDeliveryCoordinator.class);
    private final AnalyticsExporter exporter;
    private boolean failed;

    public AnalyticsDeliveryCoordinator(AnalyticsExporter exporter) {
        this.exporter = exporter;
    }

    // Ручной запуск и scheduler делят один монитор, включая состояние отказа и восстановления.
    public synchronized int flush() {
        int delivered = exporter.flush();
        if (delivered > 0 && failed) {
            LOG.info("Analytics delivery recovered");
            failed = false;
        }
        return delivered;
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
