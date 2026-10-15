package dev.principalwater.store.analytics;

import java.util.List;

public record AnalyticsDashboard(State state, AnalyticsPeriod period, QueueStatus queue, AnalyticsSummary summary,
                                 AnalyticsSummary previous, List<DailyMetric> daily, List<AnalyticsRow> products) {
    public enum State { AVAILABLE, DISABLED, UNAVAILABLE }

    public AnalyticsDashboard {
        daily = List.copyOf(daily);
        products = List.copyOf(products);
    }

    public boolean available() { return state == State.AVAILABLE; }

    public static AnalyticsDashboard inactive(State state, AnalyticsPeriod period, QueueStatus queue) {
        // Отсутствие соединения не превращается в выдуманные нулевые показатели.
        return new AnalyticsDashboard(state, period, queue, null, null, List.of(), List.of());
    }
}
