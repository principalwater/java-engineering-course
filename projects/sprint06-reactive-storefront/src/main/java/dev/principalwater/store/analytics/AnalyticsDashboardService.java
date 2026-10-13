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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AnalyticsDashboardService {
    private static final int TOP_PRODUCTS = 10;
    private static final BigDecimal MAXIMUM_PERCENT = BigDecimal.valueOf(100);
    private final AnalyticsQueue queue;
    private final ObjectProvider<ClickHouseClient> clickhouse;
    private final ObjectProvider<AnalyticsDeliveryCoordinator> delivery;
    private final Clock clock;

    public AnalyticsDashboardService(AnalyticsQueue queue, ObjectProvider<ClickHouseClient> clickhouse,
                                     ObjectProvider<AnalyticsDeliveryCoordinator> delivery, Clock clock) {
        this.queue = queue;
        this.clickhouse = clickhouse;
        this.delivery = delivery;
        this.clock = clock;
    }

    public Mono<AnalyticsDashboard> load(AnalyticsPeriod period) {
        return Mono.defer(() -> queue.status().flatMap(status -> {
            var client = clickhouse.getIfAvailable();
            if (client == null) {
                return Mono.just(AnalyticsDashboard.inactive(AnalyticsDashboard.State.DISABLED, period, status));
            }
            var end = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            var start = end.minusDays(period.getDays() - 1L);
            return queue.sourceId().flatMap(source -> client.dashboard(source, start, end))
                    .map(rows -> available(period, status, start, end, rows))
                    .onErrorResume(this::isUnavailable, error -> Mono.just(AnalyticsDashboard.inactive(
                            AnalyticsDashboard.State.UNAVAILABLE, period, status)));
        }));
    }

    public Mono<DeliveryResult> sync() {
        return Mono.defer(() -> {
            var coordinator = delivery.getIfAvailable();
            if (coordinator == null) {
                return Mono.error(new StoreException(StoreError.ANALYTICS_UNAVAILABLE));
            }
            return coordinator.flush().onErrorResume(this::isUnavailable,
                    error -> Mono.just(DeliveryResult.unavailable()));
        });
    }

    private boolean isUnavailable(Throwable error) {
        return error instanceof StoreException failure && failure.getError() == StoreError.ANALYTICS_UNAVAILABLE;
    }

    private AnalyticsDashboard available(AnalyticsPeriod period, QueueStatus status, LocalDate start,
                                         LocalDate end, List<AnalyticsRow> rows) {
        var total = summary(rows, AnalyticsBucket.TOTAL);
        var previous = summary(rows, AnalyticsBucket.PREVIOUS);
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
                total, previous, daily, products);
    }

    private AnalyticsSummary summary(List<AnalyticsRow> rows, AnalyticsBucket bucket) {
        var total = rows.stream().filter(row -> row.bucket() == bucket).findFirst()
                .orElseThrow(() -> new StoreException(StoreError.ANALYTICS_UNAVAILABLE));
        return new AnalyticsSummary(total.orders(), total.units(), total.orderValue());
    }
}
