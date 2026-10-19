package dev.principalwater.bank.runtime.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.principalwater.bank.runtime.workflow.MoneyWorkflow;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class DeliveryWorkersTest {
    @Test
    void blockedDeliveryUsesTwoWorkersAndDoesNotPreventIntentRecovery() throws Exception {
        RestOutbox outbox = mock(RestOutbox.class);
        MoneyWorkflow workflow = mock(MoneyWorkflow.class);
        List<UUID> events = IntStream.range(0, 16).mapToObj(index -> UUID.randomUUID()).toList();
        UUID command = UUID.randomUUID();
        when(outbox.pendingIds()).thenReturn(events).thenReturn(List.of());
        when(workflow.pendingIds()).thenReturn(List.of(command)).thenReturn(List.of());
        CountDownLatch twoDeliveries = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(16);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        doAnswer(
                        call -> {
                            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                            twoDeliveries.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Delivery fixture timed out");
                                }
                            } finally {
                                active.decrementAndGet();
                                completed.countDown();
                            }
                            return null;
                        })
                .when(outbox)
                .deliver(any());
        doAnswer(
                        call -> {
                            recovered.countDown();
                            return null;
                        })
                .when(workflow)
                .recover(command);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("moneyWorkflow", workflow);
        try (DeliveryConfiguration.DeliveryWorkers workers =
                new DeliveryConfiguration.DeliveryWorkers(
                        outbox, beans.getBeanProvider(MoneyWorkflow.class))) {
            workers.start();
            assertThat(recovered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(twoDeliveries.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(2);
            release.countDown();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(2);
        } finally {
            release.countDown();
        }
    }
}
