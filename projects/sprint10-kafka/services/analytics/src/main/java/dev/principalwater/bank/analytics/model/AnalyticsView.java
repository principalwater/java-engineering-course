package dev.principalwater.bank.analytics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.principalwater.bank.contract.AnalyticsEventType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public record AnalyticsView(
        boolean available,
        List<Day> days,
        Long operationsCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long accountsCount,
        Long viewCount,
        Instant updatedAt,
        List<Breakdown> breakdown,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID accountId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String month) {
    public AnalyticsView(
            boolean available,
            List<Day> days,
            Long operationsCount,
            Long accountsCount,
            Long viewCount,
            Instant updatedAt) {
        this(
                available,
                days,
                operationsCount,
                accountsCount,
                viewCount,
                updatedAt,
                List.of(),
                null,
                null);
    }

    public AnalyticsView owned(UUID owner, YearMonth month) {
        return new AnalyticsView(
                available,
                days,
                operationsCount,
                accountsCount,
                viewCount,
                updatedAt,
                breakdown,
                owner,
                month.toString());
    }

    public static AnalyticsView unavailable() {
        return new AnalyticsView(false, List.of(), null, null, null, null);
    }

    public record Day(LocalDate date, long incomingMinor, long outgoingMinor) {}

    public record Breakdown(
            LocalDate date,
            AnalyticsEventType type,
            long incomingMinor,
            long outgoingMinor,
            long operationsCount) {}
}
