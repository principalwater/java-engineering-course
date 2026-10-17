package dev.principalwater.store.analytics;

import dev.principalwater.store.ReactiveTestBase;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsDashboardServiceTest extends ReactiveTestBase {
    private final UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

    @Test
    void dashboardZeroFillsItsUtcWindowRanksProductsAndRoundsTheAverageExactly() {
        var queue = mock(AnalyticsQueue.class);
        var clickhouse = mock(ClickHouseClient.class);
        UUID source = UUID.randomUUID();
        var clock = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC);
        when(queue.sourceId()).thenReturn(Mono.just(source));
        when(queue.status(cartId)).thenReturn(Mono.just(new QueueStatus(0, clock.instant(), null)));
        when(clickhouse.dashboard(source, userId, LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-12")))
                .thenReturn(Mono.just(List.of(
                        new AnalyticsRow(AnalyticsBucket.TOTAL, "1970-01-01", "", "", 3, 3, 101),
                        new AnalyticsRow(AnalyticsBucket.PREVIOUS, "1970-01-01", "", "", 0, 0, 0),
                        new AnalyticsRow(AnalyticsBucket.DAILY, "2026-10-12", "", "Alpha", 3, 3, 101),
                        new AnalyticsRow(AnalyticsBucket.PRODUCT, "1970-01-01", "zulu", "Zulu", 1, 1, 1),
                        new AnalyticsRow(AnalyticsBucket.PRODUCT, "1970-01-01", "alpha", "Alpha", 2, 2, 100))));

        StepVerifier.create(service(queue, clickhouse, null, clock).load(AnalyticsPeriod.LAST_7_DAYS, userId, cartId))
                .assertNext(view -> {
                    assertEquals(new BigDecimal("33.67"), view.summary().averageOrder());
                    assertEquals(new AnalyticsSummary(0, 0, 0), view.previous());
                    assertNull(view.previous().averageOrder());
                    assertEquals(7, view.daily().size());
                    assertEquals(LocalDate.parse("2026-10-12"), view.daily().getLast().date());
                    assertEquals(6, view.daily().stream().filter(day -> day.orderValue() == 0).count());
                    assertEquals(100, view.daily().getLast().percent());
                    assertEquals(101, view.daily().stream().mapToLong(DailyMetric::orderValue).sum());
                    assertEquals(List.of("alpha", "zulu"), view.products().stream().map(AnalyticsRow::sku).toList());
                }).expectComplete().verify(TIMEOUT);
    }

    @Test
    void disabledAnalyticsRetainsTheQueueWithoutInventingMetricsOrAllowingDelivery() {
        var queue = mock(AnalyticsQueue.class);
        var pending = new QueueStatus(3, null, Instant.parse("2026-10-12T10:00:00Z"));
        when(queue.status(cartId)).thenReturn(Mono.just(pending));
        var service = service(queue, null, null, Clock.systemUTC());

        StepVerifier.create(service.load(AnalyticsPeriod.LAST_7_DAYS, userId, cartId)).assertNext(view -> {
            assertEquals(AnalyticsDashboard.State.DISABLED, view.state());
            assertEquals(pending, view.queue());
            assertNull(view.summary());
            assertNull(view.previous());
        }).expectComplete().verify(TIMEOUT);
        expectError(service.sync(), StoreError.ANALYTICS_UNAVAILABLE);
    }

    @Test
    void warehouseOutageIsDistinctFromZeroOrBusyDeliveryAndUnrelatedFailuresStillPropagate() {
        var queue = mock(AnalyticsQueue.class);
        var clickhouse = mock(ClickHouseClient.class);
        var delivery = mock(AnalyticsDeliveryCoordinator.class);
        var clock = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC);
        UUID source = UUID.randomUUID();
        when(queue.sourceId()).thenReturn(Mono.just(source));
        when(queue.status(cartId)).thenReturn(Mono.just(new QueueStatus(2, null, clock.instant())));
        var outage = new StoreException(StoreError.ANALYTICS_UNAVAILABLE);
        var unrelated = new StoreException(StoreError.INVALID_REQUEST);
        when(clickhouse.dashboard(source, userId, LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-12")))
                .thenReturn(Mono.error(outage), Mono.error(unrelated), Mono.just(List.of(
                        new AnalyticsRow(AnalyticsBucket.TOTAL, "1970-01-01", "", "", 0, 0, 0))), Mono.just(List.of(
                        new AnalyticsRow(AnalyticsBucket.TOTAL, "1970-01-01", "", "", 0, 0, 0),
                        new AnalyticsRow(AnalyticsBucket.PREVIOUS, "1970-01-01", "", "", 0, 0, 0))));
        when(delivery.flush()).thenReturn(Mono.just(DeliveryResult.delivered(0)),
                Mono.just(DeliveryResult.inProgress()), Mono.error(outage), Mono.error(unrelated));
        var service = service(queue, clickhouse, delivery, clock);

        StepVerifier.create(service.load(AnalyticsPeriod.LAST_7_DAYS, userId, cartId)).assertNext(view -> {
            assertEquals(AnalyticsDashboard.State.UNAVAILABLE, view.state());
            assertNull(view.summary());
            assertNull(view.previous());
        }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(service.sync()).expectNext(DeliveryResult.delivered(0)).expectComplete().verify(TIMEOUT);
        StepVerifier.create(service.sync()).expectNext(DeliveryResult.inProgress()).expectComplete().verify(TIMEOUT);
        StepVerifier.create(service.sync()).expectNext(DeliveryResult.unavailable()).expectComplete().verify(TIMEOUT);
        StepVerifier.create(service.sync()).expectErrorSatisfies(failure -> assertSame(unrelated, failure)).verify(TIMEOUT);
        StepVerifier.create(service.load(AnalyticsPeriod.LAST_7_DAYS, userId, cartId))
                .expectErrorSatisfies(failure -> assertSame(unrelated, failure)).verify(TIMEOUT);
        // Неполный ответ без предыдущего периода также не становится выдуманным сравнением с нулём.
        StepVerifier.create(service.load(AnalyticsPeriod.LAST_7_DAYS, userId, cartId))
                .assertNext(view -> assertEquals(AnalyticsDashboard.State.UNAVAILABLE, view.state()))
                .expectComplete().verify(TIMEOUT);
        StepVerifier.create(service.load(AnalyticsPeriod.LAST_7_DAYS, userId, cartId)).assertNext(view -> {
            assertEquals(AnalyticsDashboard.State.AVAILABLE, view.state());
            assertEquals(0, view.summary().orders());
            assertEquals(0, view.summary().orderValue());
            assertNull(view.summary().averageOrder());
            assertNull(view.previous().averageOrder());
        }).expectComplete().verify(TIMEOUT);
    }

    private AnalyticsDashboardService service(AnalyticsQueue queue, ClickHouseClient client,
                                               AnalyticsDeliveryCoordinator delivery, Clock clock) {
        var beans = new StaticListableBeanFactory();
        if (client != null) {
            beans.addBean("clickhouse", client);
        }
        if (delivery != null) {
            beans.addBean("delivery", delivery);
        }
        return new AnalyticsDashboardService(queue, beans.getBeanProvider(ClickHouseClient.class),
                beans.getBeanProvider(AnalyticsDeliveryCoordinator.class), clock);
    }
}
