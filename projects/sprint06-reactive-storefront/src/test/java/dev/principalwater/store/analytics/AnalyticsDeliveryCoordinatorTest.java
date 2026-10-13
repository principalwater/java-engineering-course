package dev.principalwater.store.analytics;

import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsDeliveryCoordinatorTest {
    @Test
    void manualAndScheduledDeliveryCannotOverlapInsideOneProcess() throws Exception {
        var exporter = mock(AnalyticsExporter.class);
        var coordinator = new AnalyticsDeliveryCoordinator(exporter);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var scheduledStarted = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        when(exporter.flush()).thenAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return 1;
            } finally {
                active.decrementAndGet();
            }
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var manual = workers.submit(coordinator::flush);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var scheduled = workers.submit(() -> {
                scheduledStarted.countDown();
                coordinator.deliverPendingOrders();
            });
            assertTrue(scheduledStarted.await(5, TimeUnit.SECONDS));
            // Второй маршрут уже запущен, но не должен войти в доставку до завершения первого.
            assertThrows(TimeoutException.class, () -> scheduled.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, manual.get(5, TimeUnit.SECONDS));
            scheduled.get(5, TimeUnit.SECONDS);
            assertEquals(1, maximum.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aFailedScheduledAttemptDoesNotPreventManualRecovery() {
        var exporter = mock(AnalyticsExporter.class);
        doThrow(new StoreException(StoreError.ANALYTICS_UNAVAILABLE)).doReturn(1).when(exporter).flush();
        var coordinator = new AnalyticsDeliveryCoordinator(exporter);

        coordinator.deliverPendingOrders();
        assertEquals(1, coordinator.flush());
    }
}
