package dev.principalwater.bank.analytics.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.model.ActivitySnapshot;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.AnalyticsEventType;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.UiActivityEvent;
import dev.principalwater.bank.contract.UiActivitySection;
import dev.principalwater.bank.contract.UiActivityType;
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
                                        AnalyticsEventType.CABINET_VIEW, 0, "2026-10-17T02:00:00Z"),
                                activity(
                                        AnalyticsEventType.PROFILE_UPDATED,
                                        0,
                                        "2026-10-17T02:10:00Z"),
                                activity(
                                        AnalyticsEventType.NOTIFICATION_READ,
                                        0,
                                        "2026-10-17T02:20:00Z")));
        assertEquals(
                new AnalyticsView(
                        true,
                        List.of(
                                new AnalyticsView.Day(LocalDate.of(2026, 10, 16), 29, 0),
                                new AnalyticsView.Day(LocalDate.of(2026, 10, 17), 150, 17)),
                        3L,
                        null,
                        1L,
                        Instant.parse("2026-10-17T02:20:00Z"),
                        List.of(
                                new AnalyticsView.Breakdown(
                                        LocalDate.of(2026, 10, 16),
                                        AnalyticsEventType.DEPOSIT,
                                        29,
                                        0,
                                        1),
                                new AnalyticsView.Breakdown(
                                        LocalDate.of(2026, 10, 17),
                                        AnalyticsEventType.WITHDRAW,
                                        0,
                                        17,
                                        1),
                                new AnalyticsView.Breakdown(
                                        LocalDate.of(2026, 10, 17),
                                        AnalyticsEventType.TRANSFER_IN,
                                        150,
                                        0,
                                        1)),
                        null,
                        null),
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

    @Test
    void cassandraSnapshotDeduplicatesBeforeGroupingAndKeepsBackendAndUiDimensionsSeparate() {
        CassandraActivityStore.Activity backend =
                activity(AnalyticsEventType.NOTIFICATION_READ, 0, "2026-10-16T23:59:59Z");
        UiActivityEvent ui =
                new UiActivityEvent(
                        UUID.randomUUID(),
                        OWNER,
                        UiActivityType.SCREEN_OPENED,
                        UiActivitySection.NOTIFICATIONS,
                        NOW);
        assertEquals(
                List.of(
                        new ActivitySnapshot.Row(
                                OWNER,
                                LocalDate.of(2026, 10, 16),
                                "BACKEND",
                                "NOTIFICATION_READ",
                                "NONE",
                                1,
                                9),
                        new ActivitySnapshot.Row(
                                OWNER,
                                LocalDate.of(2026, 10, 17),
                                "UI",
                                "SCREEN_OPENED",
                                "NOTIFICATIONS",
                                1,
                                9)),
                ActivitySnapshot.build(OWNER, 9, List.of(backend, backend), List.of(ui, ui)));
        for (AnalyticsEventType type :
                List.of(
                        AnalyticsEventType.PROFILE_UPDATED,
                        AnalyticsEventType.NOTIFICATION_AVAILABLE,
                        AnalyticsEventType.NOTIFICATION_READ)) {
            BankEvent valid = new BankEvent(UUID.randomUUID(), OWNER, type.name(), 0, 0, NOW);
            assertDoesNotThrow(() -> AnalyticsEventType.validateBackend(valid, NOW));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AnalyticsEventType.validateFinancial(valid, NOW));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            AnalyticsEventType.validateBackend(
                                    new BankEvent(valid.eventId(), OWNER, type.name(), 1, 0, NOW),
                                    NOW));
        }
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
