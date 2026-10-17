package dev.principalwater.bank.analytics.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.model.AnalyticsEventType;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivitySummaryTest {
    private static final UUID OWNER = UUID.fromString("880d9f9e-a2b4-4622-b130-d1a6d2ed1c37");
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    void monthSummaryKeepsExactMinorUnitsAndSeparatesViewsFromFinancialPostingsInUtc() {
        AnalyticsView view =
                ActivitySummary.personal(
                        List.of(
                                activity(
                                        AnalyticsEventType.DEPOSIT, 29, "2026-10-16T23:59:59.123Z"),
                                activity(AnalyticsEventType.WITHDRAW, -17, "2026-10-17T00:00:00Z"),
                                activity(
                                        AnalyticsEventType.TRANSFER_IN,
                                        150,
                                        "2026-10-17T01:00:00Z"),
                                activity(
                                        AnalyticsEventType.CABINET_VIEW,
                                        0,
                                        "2026-10-17T02:00:00Z")));
        assertEquals(
                new AnalyticsView(
                        true,
                        List.of(
                                new AnalyticsView.Day(LocalDate.of(2026, 10, 16), 29, 0),
                                new AnalyticsView.Day(LocalDate.of(2026, 10, 17), 150, 17)),
                        3L,
                        null,
                        1L,
                        Instant.parse("2026-10-17T02:00:00Z")),
                view);
        assertEquals(
                new AnalyticsView(true, List.of(), 0L, null, 0L, null),
                ActivitySummary.personal(List.of()));
        assertFalse(AnalyticsView.unavailable().available());
        assertNull(AnalyticsView.unavailable().operationsCount());
    }

    @Test
    void onlySignedBoundedFinancialFactsAreAcceptedAtTheInternalBoundary() {
        for (AnalyticsEventType type :
                List.of(
                        AnalyticsEventType.DEPOSIT,
                        AnalyticsEventType.WITHDRAW,
                        AnalyticsEventType.TRANSFER_IN,
                        AnalyticsEventType.TRANSFER_OUT)) {
            BankEvent valid = event(type.name(), BankLimits.MAX_AMOUNT_MINOR * type.getSign(), NOW);
            assertDoesNotThrow(() -> AnalyticsEventType.validateFinancial(valid, NOW));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            AnalyticsEventType.validateFinancial(
                                    event(type.name(), -valid.amountMinor(), NOW), NOW));
        }
        for (long amount : new long[] {0, Long.MIN_VALUE, BankLimits.MAX_AMOUNT_MINOR + 1}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AnalyticsEventType.validateFinancial(event("DEPOSIT", amount, NOW), NOW));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> AnalyticsEventType.validateFinancial(event("CABINET_VIEW", 0, NOW), NOW));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnalyticsEventType.validateFinancial(
                                event("DEPOSIT", 1, NOW.plusSeconds(61)), NOW));
    }

    private CassandraActivityStore.Activity activity(
            AnalyticsEventType type, long amount, String occurredAt) {
        return new CassandraActivityStore.Activity(
                UUID.randomUUID(), type, amount, Instant.parse(occurredAt));
    }

    private BankEvent event(String type, long amount, Instant occurredAt) {
        return new BankEvent(UUID.randomUUID(), OWNER, type, amount, 100, occurredAt);
    }
}
