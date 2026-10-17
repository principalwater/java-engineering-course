package dev.principalwater.bank.runtime.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.workflow.MoneyWorkflow;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

public class DeliveryConfiguration {
    @Bean
    RestOutbox restOutbox(JdbcTemplate jdbc, ObjectMapper mapper, ServiceClient client) {
        return new RestOutbox(jdbc, mapper, client);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "bank.delivery.enabled",
            havingValue = "true",
            matchIfMissing = true)
    DeliveryWorkers deliveryWorkers(RestOutbox outbox, ObjectProvider<MoneyWorkflow> workflow) {
        return new DeliveryWorkers(outbox, workflow);
    }

    static final class DeliveryWorkers implements AutoCloseable {
        private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorkers.class);
        private static final int BATCH_SIZE = 16;
        private final ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().name("bank-recovery-timer-", 0).factory());
        private final ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(BATCH_SIZE),
                        Thread.ofPlatform().name("bank-recovery-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        private final RestOutbox outbox;
        private final ObjectProvider<MoneyWorkflow> workflow;

        DeliveryWorkers(RestOutbox outbox, ObjectProvider<MoneyWorkflow> workflow) {
            this.outbox = outbox;
            this.workflow = workflow;
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        public void start() {
            scheduler.scheduleWithFixedDelay(() -> safely(this::dispatch), 1, 1, TimeUnit.SECONDS);
        }

        private void dispatch() {
            MoneyWorkflow money = workflow.getIfAvailable();
            List<UUID> events = outbox.pendingIds();
            List<UUID> commands = money == null ? List.of() : money.pendingIds();
            // Общие два worker и очередь из 16; чередование не даёт outbox вытеснить money
            // recovery.
            for (int index = 0; index < BATCH_SIZE; index++) {
                if (index < commands.size()) {
                    UUID id = commands.get(index);
                    if (!submit(() -> money.recover(id))) {
                        return;
                    }
                }
                if (index < events.size()) {
                    UUID id = events.get(index);
                    if (!submit(() -> outbox.deliver(id))) {
                        return;
                    }
                }
            }
        }

        private boolean submit(Runnable task) {
            try {
                workers.execute(() -> safely(task));
                return true;
            } catch (RejectedExecutionException exception) {
                return false;
            }
        }

        private void safely(Runnable task) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                LOG.warn("Recovery batch unavailable: {}", exception.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
            workers.shutdownNow();
            try {
                workers.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
