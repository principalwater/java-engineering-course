package dev.principalwater.store.analytics;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        var view = new AnalyticsDashboardService(queue, clickhouse, clock).load(AnalyticsPeriod.LAST_7_DAYS);
        assertEquals(new BigDecimal("33.67"), view.summary().averageOrder());
        assertEquals(7, view.daily().size());
        assertEquals(LocalDate.parse("2026-10-12"), view.daily().getLast().date());
        assertEquals(6, view.daily().stream().filter(day -> day.orderValue() == 0).count());
        assertEquals(100, view.daily().getLast().percent());
        assertEquals(101, view.daily().stream().mapToLong(DailyMetric::orderValue).sum());
        assertEquals(List.of("alpha", "zulu"), view.products().stream().map(AnalyticsRow::sku).toList());
    }
}
