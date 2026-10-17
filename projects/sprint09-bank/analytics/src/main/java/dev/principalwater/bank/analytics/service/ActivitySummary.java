package dev.principalwater.bank.analytics.service;

import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.model.AnalyticsEventType;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

final class ActivitySummary {
    private ActivitySummary() {}

    static AnalyticsView personal(List<CassandraActivityStore.Activity> events) {
        TreeMap<LocalDate, long[]> days = new TreeMap<>();
        long operations = 0;
        long views = 0;
        Instant updatedAt = null;
        for (CassandraActivityStore.Activity event : events) {
            if (updatedAt == null || event.occurredAt().isAfter(updatedAt)) {
                updatedAt = event.occurredAt();
            }
            if (event.type() == AnalyticsEventType.CABINET_VIEW) {
                views++;
                continue;
            }
            operations++;
            long[] amounts =
                    days.computeIfAbsent(
                            event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate(),
                            ignored -> new long[2]);
            if (event.amountMinor() > 0) {
                amounts[0] = Math.addExact(amounts[0], event.amountMinor());
            } else {
                amounts[1] = Math.subtractExact(amounts[1], event.amountMinor());
            }
        }
        List<AnalyticsView.Day> result = new ArrayList<>();
        days.forEach(
                (date, amounts) -> result.add(new AnalyticsView.Day(date, amounts[0], amounts[1])));
        return new AnalyticsView(true, result, operations, null, views, updatedAt);
    }
}
