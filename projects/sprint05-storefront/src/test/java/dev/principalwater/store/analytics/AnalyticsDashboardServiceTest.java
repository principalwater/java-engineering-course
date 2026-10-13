package dev.principalwater.store.analytics;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsDashboardServiceTest {
    @Test
    void dashboardZeroFillsItsUtcWindowRanksProductsAndRoundsTheAverageExactly() {
        var queue = mock(AnalyticsQueue.class);
        var clickhouse = mock(ClickHouseClient.class);
        UUID source = UUID.randomUUID();
        var clock = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC);
        when(queue.sourceId()).thenReturn(source);
        when(queue.status()).thenReturn(new QueueStatus(0, clock.instant()));
        when(clickhouse.dashboard(source, LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-12")))
                .thenReturn(List.of(
                        new AnalyticsRow(AnalyticsBucket.TOTAL, "1970-01-01", "", "", 3, 3, 101),
                        new AnalyticsRow(AnalyticsBucket.DAILY, "2026-10-12", "", "Alpha", 3, 3, 101),
                        new AnalyticsRow(AnalyticsBucket.PRODUCT, "1970-01-01", "zulu", "Zulu", 1, 1, 1),
                        new AnalyticsRow(AnalyticsBucket.PRODUCT, "1970-01-01", "alpha", "Alpha", 2, 2, 100)));

        var view = service(queue, clickhouse, null, clock).load(AnalyticsPeriod.LAST_7_DAYS);
        assertEquals(new BigDecimal("33.67"), view.summary().averageOrder());
        assertEquals(7, view.daily().size());
        assertEquals(LocalDate.parse("2026-10-12"), view.daily().getLast().date());
        assertEquals(6, view.daily().stream().filter(day -> day.orderValue() == 0).count());
        assertEquals(100, view.daily().getLast().percent());
        assertEquals(101, view.daily().stream().mapToLong(DailyMetric::orderValue).sum());
        assertEquals(List.of("alpha", "zulu"), view.products().stream().map(AnalyticsRow::sku).toList());
    }

    @Test
    void disabledAnalyticsRetainsTheQueueWithoutInventingMetricsOrAllowingDelivery() {
        var queue = mock(AnalyticsQueue.class);
        var pending = new QueueStatus(3, null);
        when(queue.status()).thenReturn(pending);
        var service = service(queue, null, null, Clock.systemUTC());

        var view = service.load(AnalyticsPeriod.LAST_7_DAYS);
        assertEquals(AnalyticsDashboard.State.DISABLED, view.state());
        assertEquals(pending, view.queue());
        assertNull(view.summary());
        assertEquals(StoreError.ANALYTICS_UNAVAILABLE,
                assertThrows(StoreException.class, service::sync).getError());
    }

    @Test
    void warehouseOutageIsDistinctFromZeroDeliveryAndUnrelatedFailuresStillPropagate() {
        var queue = mock(AnalyticsQueue.class);
        var clickhouse = mock(ClickHouseClient.class);
        var delivery = mock(AnalyticsDeliveryCoordinator.class);
        var clock = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC);
        UUID source = UUID.randomUUID();
        when(queue.sourceId()).thenReturn(source);
        when(queue.status()).thenReturn(new QueueStatus(2, null));
        var outage = new StoreException(StoreError.ANALYTICS_UNAVAILABLE);
        var unrelated = new StoreException(StoreError.INVALID_REQUEST);
        when(clickhouse.dashboard(source, LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-12")))
                .thenThrow(outage).thenThrow(unrelated);
        when(delivery.flush()).thenReturn(0).thenThrow(outage).thenThrow(unrelated);
        var service = service(queue, clickhouse, delivery, clock);

        var view = service.load(AnalyticsPeriod.LAST_7_DAYS);
        assertEquals(AnalyticsDashboard.State.UNAVAILABLE, view.state());
        assertNull(view.summary());
        assertEquals(0, service.sync().orElseThrow());
        assertFalse(service.sync().isPresent());
        assertSame(unrelated, assertThrows(StoreException.class, service::sync));
        assertSame(unrelated, assertThrows(StoreException.class, () -> service.load(AnalyticsPeriod.LAST_7_DAYS)));
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
