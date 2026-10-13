package dev.principalwater.store.analytics;

import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyticsDeliveryCoordinatorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void admissionIsLazyReportsBusyUntilCancellationAndReleasesTheDeliverySlot() {
        var exporter = mock(AnalyticsExporter.class);
        when(exporter.flush()).thenReturn(Mono.never(), Mono.just(1));
        var coordinator = new AnalyticsDeliveryCoordinator(exporter);
        var manual = coordinator.flush();
        verifyNoInteractions(exporter);

        StepVerifier.create(manual).then(() -> {
            StepVerifier.create(coordinator.flush()).expectNext(DeliveryResult.inProgress())
                    .expectComplete().verify(TIMEOUT);
            StepVerifier.create(coordinator.deliverPendingOrders()).expectComplete().verify(TIMEOUT);
            // Занятость маршрута не означает второй HTTP-запрос экспортёра.
            verify(exporter, times(1)).flush();
        }).thenCancel().verify(TIMEOUT);
        StepVerifier.create(coordinator.flush()).expectNext(DeliveryResult.delivered(1))
                .expectComplete().verify(TIMEOUT);
        verify(exporter, times(2)).flush();
    }

    @Test
    void aFailedScheduledAttemptDoesNotPreventManualRecoveryOrANextSuccessfulDelivery() {
        var exporter = mock(AnalyticsExporter.class);
        when(exporter.flush()).thenReturn(Mono.error(new StoreException(StoreError.ANALYTICS_UNAVAILABLE)),
                Mono.just(1), Mono.just(0));
        var coordinator = new AnalyticsDeliveryCoordinator(exporter);

        StepVerifier.create(coordinator.deliverPendingOrders()).expectComplete().verify(TIMEOUT);
        StepVerifier.create(coordinator.flush()).expectNext(DeliveryResult.delivered(1))
                .expectComplete().verify(TIMEOUT);
        StepVerifier.create(coordinator.flush()).expectNext(DeliveryResult.delivered(0))
                .expectComplete().verify(TIMEOUT);
    }
}
