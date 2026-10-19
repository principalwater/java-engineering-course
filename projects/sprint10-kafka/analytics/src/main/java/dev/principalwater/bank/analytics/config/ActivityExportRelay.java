package dev.principalwater.bank.analytics.config;

import dev.principalwater.bank.analytics.dao.ActivityExportQueue;
import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.dao.CassandraUiActivityStore;
import dev.principalwater.bank.analytics.dao.ClickHouseActivityMart;
import dev.principalwater.bank.analytics.model.ActivitySnapshot;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ActivityExportRelay {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "bank.analytics.delivery-enabled",
            havingValue = "true",
            matchIfMissing = true)
    Worker activityExportWorker(
            ActivityExportQueue queue,
            CassandraActivityStore backend,
            CassandraUiActivityStore ui,
            ClickHouseActivityMart mart) {
        return new Worker(queue, backend, ui, mart);
    }

    static final class Worker implements AutoCloseable {
        private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
        // Один worker без очереди обслуживает одну owner/month за тик; каждый внешний I/O конечен.
        private static final int INTERVAL_SECONDS = 1;
        private final ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().name("analytics-activity-export-", 0).factory());
        private final ActivityExportQueue queue;
        private final CassandraActivityStore backend;
        private final CassandraUiActivityStore ui;
        private final ClickHouseActivityMart mart;

        Worker(
                ActivityExportQueue queue,
                CassandraActivityStore backend,
                CassandraUiActivityStore ui,
                ClickHouseActivityMart mart) {
            this.queue = queue;
            this.backend = backend;
            this.ui = ui;
            this.mart = mart;
            timer.setRemoveOnCancelPolicy(true);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        public void start() {
            timer.scheduleWithFixedDelay(
                    this::deliver, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        }

        private void deliver() {
            ActivityExportQueue.Export export = null;
            try {
                export = queue.claim();
                if (export == null) return;
                mart.insert(
                        ActivitySnapshot.build(
                                export.ownerId(),
                                export.revision(),
                                backend.month(export.ownerId(), export.month()),
                                ui.month(export.ownerId(), export.month())));
                queue.complete(export);
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Activity export unavailable: {}",
                        failure instanceof AnalyticsUnavailable unavailable
                                ? unavailable.getCategory()
                                : failure.getClass().getSimpleName());
                if (export != null) {
                    try {
                        queue.fail(export);
                    } catch (RuntimeException releaseFailure) {
                        LOG.warn(
                                "Activity export lease unavailable: {}",
                                releaseFailure.getClass().getSimpleName());
                    }
                }
            }
        }

        @Override
        public void close() {
            timer.shutdownNow();
            try {
                if (!timer.awaitTermination(10, TimeUnit.SECONDS))
                    LOG.warn("Activity export shutdown timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
