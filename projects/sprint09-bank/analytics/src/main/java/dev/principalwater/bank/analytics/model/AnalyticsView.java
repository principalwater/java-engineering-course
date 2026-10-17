package dev.principalwater.bank.analytics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AnalyticsView(
        boolean available,
        List<Day> days,
        Long operationsCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long accountsCount,
        Long viewCount,
        Instant updatedAt) {
    public static AnalyticsView unavailable() {
        return new AnalyticsView(false, List.of(), null, null, null, null);
    }

    public record Day(LocalDate date, long incomingMinor, long outgoingMinor) {}
}
