package dev.principalwater.bank.analytics.config;

import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.service.AnalyticsPublisher;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
        // Два worker и сканирующий поток ограничивают публикации тремя без очереди в памяти.
        private static final int PUBLISH_WORKERS = 2;
        private static final int SCAN_INTERVAL_SECONDS = 1;
        private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
        private final AnalyticsInbox inbox;
        private final AnalyticsPublisher publisher;
        private final ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().name("analytics-relay-timer-", 0).factory());
        // Заполненный пул замедляет сканирование вместо переноса остатка партии на следующий тик.
        private final ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        PUBLISH_WORKERS,
                        PUBLISH_WORKERS,
                        0,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofPlatform().name("analytics-relay-", 0).factory(),
                        new ThreadPoolExecutor.CallerRunsPolicy());

        Workers(AnalyticsInbox inbox, AnalyticsPublisher publisher) {
            this.inbox = inbox;
            this.publisher = publisher;
            timer.setRemoveOnCancelPolicy(true);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        public void start() {
            timer.scheduleWithFixedDelay(
                    () -> safely(this::dispatch),
                    SCAN_INTERVAL_SECONDS,
                    SCAN_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        }

        private void dispatch() {
            while (!stopping()) {
                List<UUID> batch = inbox.pendingIds();
                if (batch.isEmpty()) {
                    return;
                }
                for (UUID id : batch) {
                    if (stopping()) {
                        return;
                    }
                    workers.execute(() -> safely(() -> deliver(id)));
                }
            }
        }

        private boolean stopping() {
            return Thread.currentThread().isInterrupted() || workers.isShutdown();
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
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
            timer.shutdownNow();
            workers.shutdownNow();
            try {
                // CallerRuns исполняет I/O на timer: оба executor входят в один конечный shutdown
                // budget.
                boolean timerStopped =
                        timer.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                long remaining = deadline - System.nanoTime();
                boolean workersStopped =
                        remaining > 0
                                ? workers.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                                : workers.isTerminated();
                if (!timerStopped || !workersStopped) {
                    LOG.warn("Analytics relay shutdown timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
