package dev.principalwater.store.analytics;

import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "store.analytics.enabled", havingValue = "true")
public class AnalyticsDashboardService {
    private static final int TOP_PRODUCTS = 10;
    private static final BigDecimal MAXIMUM_PERCENT = BigDecimal.valueOf(100);
    private final AnalyticsQueue queue;
    private final ClickHouseClient clickhouse;
    private final Clock clock;

    public AnalyticsDashboardService(AnalyticsQueue queue, ClickHouseClient clickhouse, Clock clock) {
        this.queue = queue;
        this.clickhouse = clickhouse;
        this.clock = clock;
    }

    public AnalyticsDashboard load(AnalyticsPeriod period) {
        var status = queue.status();
        var end = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var start = end.minusDays(period.getDays() - 1L);
        var rows = clickhouse.dashboard(queue.sourceId(), start, end);
        var total = rows.stream().filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst()
                .orElseThrow(() -> new StoreException(StoreError.ANALYTICS_UNAVAILABLE));
        var byDate = rows.stream().filter(row -> row.bucket() == AnalyticsBucket.DAILY)
                .collect(Collectors.toMap(row -> LocalDate.parse(row.day()), Function.identity()));
        long maximum = byDate.values().stream().mapToLong(AnalyticsRow::orderValue).max().orElse(0);
        List<DailyMetric> daily = start.datesUntil(end.plusDays(1)).map(date -> {
            var row = byDate.get(date);
            if (row == null) {
                return new DailyMetric(date, 0, 0, 0, 0);
            }
            int percent = maximum == 0 ? 0 : BigDecimal.valueOf(row.orderValue()).multiply(MAXIMUM_PERCENT)
                    .divide(BigDecimal.valueOf(maximum), 0, RoundingMode.HALF_UP).intValueExact();
            return new DailyMetric(date, row.orders(), row.units(), row.orderValue(), percent);
        }).toList();
        var products = rows.stream().filter(row -> row.bucket() == AnalyticsBucket.PRODUCT)
                .sorted(Comparator.comparingLong(AnalyticsRow::units).reversed()
                        .thenComparing(Comparator.comparingLong(AnalyticsRow::orderValue).reversed())
                        .thenComparing(AnalyticsRow::sku))
                .limit(TOP_PRODUCTS).toList();
        return new AnalyticsDashboard(AnalyticsDashboard.State.AVAILABLE, period, status,
                new AnalyticsSummary(total.orders(), total.units(), total.orderValue()), daily, products);
    }
}
