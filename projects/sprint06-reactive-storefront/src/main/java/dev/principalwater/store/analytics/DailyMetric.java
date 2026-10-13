package dev.principalwater.store.analytics;

import java.time.LocalDate;

public record DailyMetric(LocalDate date, long orders, long units, long orderValue, int percent) {
}
