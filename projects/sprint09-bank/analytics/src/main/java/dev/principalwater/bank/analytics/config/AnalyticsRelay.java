package dev.principalwater.bank.analytics.config;

import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.service.AnalyticsPublisher;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AnalyticsRelay {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "bank.analytics.delivery-enabled",
            havingValue = "true",
            matchIfMissing = true)
    Workers analyticsWorkers(AnalyticsInbox inbox, AnalyticsPublisher publisher) {
        return new Workers(inbox, publisher);
    }

    static final class Workers implements AutoCloseable {
        private static final Logger LOG = LoggerFactory.getLogger(Workers.class);
        private final AnalyticsInbox inbox;
        private final AnalyticsPublisher publisher;
        private final ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().name("analytics-relay-timer-", 0).factory());
        // Два одновременных publish и очередь нулевой длины ограничивают память и нагрузку при
        // отказе Kafka.
        private final ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofPlatform().name("analytics-relay-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());

        Workers(AnalyticsInbox inbox, AnalyticsPublisher publisher) {
            this.inbox = inbox;
            this.publisher = publisher;
            timer.setRemoveOnCancelPolicy(true);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        public void start() {
            timer.scheduleWithFixedDelay(() -> safely(this::dispatch), 1, 1, TimeUnit.SECONDS);
        }

        private void dispatch() {
            for (UUID id : inbox.pendingIds()) {
                try {
                    workers.execute(() -> safely(() -> deliver(id)));
                } catch (RejectedExecutionException exception) {
                    return;
                }
            }
        }

        private void deliver(UUID id) {
            AnalyticsInbox.Delivery delivery = inbox.claim(id);
            if (delivery == null) {
                return;
            }
            boolean failed = false;
            try {
                publisher.publish(delivery.event());
            } catch (AnalyticsUnavailable exception) {
                failed = true;
                log(exception);
            } finally {
                // Broker ACK не завершает доставку: до обоих sink ACK inbox повторит тот же
                // eventId.
                inbox.release(delivery, failed);
            }
        }

        private void log(AnalyticsUnavailable failure) {
            LOG.warn("Analytics Kafka delivery unavailable: {}", failure.getCategory());
        }

        private void safely(Runnable task) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                LOG.warn("Analytics relay unavailable: {}", exception.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            timer.shutdownNow();
            workers.shutdownNow();
            try {
                workers.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
