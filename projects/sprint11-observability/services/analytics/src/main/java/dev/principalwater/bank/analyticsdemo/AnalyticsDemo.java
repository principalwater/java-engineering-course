package dev.principalwater.bank.analyticsdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.dao.ActivityExportQueue;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.contract.AnalyticsEventType;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.UiActivityRequest;
import dev.principalwater.bank.contract.UiActivitySection;
import dev.principalwater.bank.contract.UiActivityType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Только демонстрационная проекция: эти владельцы не являются банковскими счетами или клиентами.
public final class AnalyticsDemo {
    private static final int DAYS = 14;
    private static final int KOPECKS_PER_RUBLE = 100;
    private static final List<UUID> OWNERS =
            List.of(
                    UUID.fromString("de110000-0000-4000-8000-000000000001"),
                    UUID.fromString("de110000-0000-4000-8000-000000000002"),
                    UUID.fromString("de110000-0000-4000-8000-000000000003"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AnalyticsDemo(
            JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = transaction;
        this.clock = clock;
    }

    public int load(boolean enabled, String namespace, LocalDate lastDay) {
        if (!enabled
                || namespace == null
                || !List.of("bank11-dev", "bank11-test").contains(namespace))
            throw new IllegalArgumentException(
                    "Analytics demo requires an explicit non-production namespace");
        if (lastDay == null
                || !lastDay.equals(LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1)))
            throw new IllegalArgumentException(
                    "Analytics demo end date must be the last complete UTC day");
        return transaction.execute(
                status -> {
                    verifyDatabase(namespace);
                    int count = 0;
                    for (LocalDate day = lastDay.minusDays(DAYS - 1);
                            !day.isAfter(lastDay);
                            day = day.plusDays(1)) {
                        // Часы принадлежат только CLI-экземпляру Inbox: HTTP по-прежнему ставит
                        // серверное время.
                        Clock fixtureClock =
                                Clock.fixed(
                                        day.atTime(16, 0).toInstant(ZoneOffset.UTC),
                                        ZoneOffset.UTC);
                        AnalyticsInbox inbox =
                                new AnalyticsInbox(
                                        jdbc, mapper, fixtureClock, new ActivityExportQueue(jdbc));
                        for (int ownerIndex = 0; ownerIndex < OWNERS.size(); ownerIndex++)
                            count += day(inbox, day, OWNERS.get(ownerIndex), ownerIndex);
                    }
                    return count;
                });
    }

    private void verifyDatabase(String namespace) {
        Boolean allowed =
                jdbc.queryForObject(
                        """
                SELECT current_schema() = 'analytics' AND
                    ((current_database() = 'bank' AND current_user = 'analytics') OR
                     (? = 'bank11-test' AND current_database() = 'bank_test'))
                """,
                        Boolean.class,
                        namespace);
        if (!Boolean.TRUE.equals(allowed))
            throw new IllegalStateException("Analytics demo database identity is not allowed");
    }

    private int day(AnalyticsInbox inbox, LocalDate day, UUID owner, int ownerIndex) {
        int variation = Math.floorMod(day.toEpochDay() + ownerIndex, 7);
        // Суммы заданы в рублях и переводятся в целые копейки; floating point в деньгах нет.
        long deposit = (1_000L + 250L * variation + 500L * ownerIndex) * KOPECKS_PER_RUBLE;
        backend(inbox, day, owner, AnalyticsEventType.DEPOSIT, deposit, deposit);
        int operations = 1;
        if (variation % 2 == 0) {
            long withdrawal = deposit / 4;
            backend(
                    inbox,
                    day,
                    owner,
                    AnalyticsEventType.WITHDRAW,
                    -withdrawal,
                    deposit - withdrawal);
            operations++;
        }
        int count = operations;
        for (int operation = 0; operation < operations; operation++) {
            backend(inbox, day, owner, AnalyticsEventType.NOTIFICATION_AVAILABLE, 0, 0, operation);
            count++;
            if ((variation + operation) % 3 != 0) {
                backend(inbox, day, owner, AnalyticsEventType.NOTIFICATION_READ, 0, 0, operation);
                count++;
            }
        }
        for (int screen = 0; screen <= variation % 3 + 1; screen++) {
            inbox.acceptUi(
                    new UiActivityRequest(
                            id(day, owner, "SCREEN_OPENED", screen),
                            UiActivityType.SCREEN_OPENED,
                            screen % 2 == 0
                                    ? UiActivitySection.HISTORY
                                    : UiActivitySection.ANALYTICS),
                    owner);
            count++;
        }
        if (variation % 3 == 0) {
            inbox.acceptUi(
                    new UiActivityRequest(
                            id(day, owner, "FORM_OPENED", 0),
                            UiActivityType.FORM_OPENED,
                            UiActivitySection.PAYMENTS),
                    owner);
            count++;
        }
        if (variation == 0) {
            backend(inbox, day, owner, AnalyticsEventType.PROFILE_UPDATED, 0, 0);
            count++;
        }
        return count;
    }

    private void backend(
            AnalyticsInbox inbox,
            LocalDate day,
            UUID owner,
            AnalyticsEventType type,
            long amount,
            long balance) {
        backend(inbox, day, owner, type, amount, balance, 0);
    }

    private void backend(
            AnalyticsInbox inbox,
            LocalDate day,
            UUID owner,
            AnalyticsEventType type,
            long amount,
            long balance,
            int sequence) {
        inbox.acceptBackend(
                new BankEvent(
                        id(day, owner, type.name(), sequence),
                        owner,
                        type.name(),
                        amount,
                        balance,
                        day.atTime(9, sequence).toInstant(ZoneOffset.UTC)));
    }

    private UUID id(LocalDate day, UUID owner, String type, int sequence) {
        // Абсолютная дата сохраняет ID и payload даже при перекрывающихся 14-дневных окнах.
        return UUID.nameUUIDFromBytes(
                ("bank.analytics.demo.v1/" + day + "/" + owner + "/" + type + "/" + sequence)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
