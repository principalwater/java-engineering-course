package dev.principalwater.bank.messaging;

import dev.principalwater.bank.runtime.delivery.DeliveryPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeliveryWorkers implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorkers.class);
    private final List<RecoverableDelivery> deliveries;
    private int first;
    private final ScheduledThreadPoolExecutor timer =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().name("bank-recovery-timer-", 0).factory());
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(DeliveryPolicy.BATCH_SIZE),
                    Thread.ofPlatform().name("bank-recovery-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());

    public DeliveryWorkers(List<RecoverableDelivery> deliveries) {
        this.deliveries = List.copyOf(deliveries);
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public void start() {
        timer.scheduleWithFixedDelay(() -> safely(this::dispatch), 1, 1, TimeUnit.SECONDS);
    }

    private void dispatch() {
        List<List<UUID>> pending = new ArrayList<>();
        for (RecoverableDelivery delivery : deliveries) pending.add(delivery.pendingIds());
        if (deliveries.isEmpty()) return;
        // Чередуем источники и первую позицию: заполненная очередь не вытесняет money recovery.
        int start = first;
        first = (first + 1) % deliveries.size();
        for (int index = 0; index < DeliveryPolicy.BATCH_SIZE; index++) {
            for (int offset = 0; offset < deliveries.size(); offset++) {
                int source = (start + offset) % deliveries.size();
                if (index < pending.get(source).size()) {
                    UUID id = pending.get(source).get(index);
                    RecoverableDelivery delivery = deliveries.get(source);
                    try {
                        workers.execute(() -> safely(() -> delivery.recover(id)));
                    } catch (RejectedExecutionException failure) {
                        return;
                    }
                }
            }
        }
    }

    private void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOG.warn("Recovery batch unavailable: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        workers.shutdownNow();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
