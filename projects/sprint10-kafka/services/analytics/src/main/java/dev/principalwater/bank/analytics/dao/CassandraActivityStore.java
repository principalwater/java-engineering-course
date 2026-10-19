package dev.principalwater.bank.analytics.dao;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.contract.AnalyticsEventType;
import dev.principalwater.bank.contract.BankEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class CassandraActivityStore {
    static final int MAX_MONTH_EVENTS = 1000;
    static final Duration RETENTION = Duration.ofDays(180);
    private final CqlSession session;
    private final PreparedStatement insert;
    private final PreparedStatement select;
    private final Clock clock;

    public CassandraActivityStore(CqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
        session.execute(
                """
                CREATE TABLE IF NOT EXISTS activity_by_owner_month (
                    owner_id uuid, month text, occurred_at timestamp, event_id uuid, event_type text,
                    amount_minor bigint, PRIMARY KEY ((owner_id, month), occurred_at, event_id))
                WITH CLUSTERING ORDER BY (occurred_at DESC, event_id ASC)
                AND default_time_to_live = 15552000
                """);
        insert =
                session.prepare(
                        """
                INSERT INTO activity_by_owner_month(owner_id, month, occurred_at, event_id, event_type, amount_minor)
                VALUES (?, ?, ?, ?, ?, ?) USING TIMESTAMP ? AND TTL ?
                """);
        select =
                session.prepare(
                        """
                SELECT event_id, event_type, amount_minor, occurred_at FROM activity_by_owner_month
                WHERE owner_id = ? AND month = ? LIMIT ?
                """);
    }

    public void insert(BankEvent event) {
        long remaining =
                Duration.between(clock.instant(), event.occurredAt().plus(RETENTION)).getSeconds();
        if (remaining <= 0) {
            return;
        }
        try {
            long timestampMicros =
                    Math.addExact(
                            Math.multiplyExact(event.occurredAt().getEpochSecond(), 1_000_000),
                            event.occurredAt().getNano() / 1000);
            // TTL уменьшается при replay: повтор не продлевает срок жизни проекции.
            session.execute(
                    insert.bind(
                            event.ownerId(),
                            month(event.occurredAt()).toString(),
                            event.occurredAt(),
                            event.eventId(),
                            event.type(),
                            event.amountMinor(),
                            timestampMicros,
                            Math.toIntExact(Math.min(RETENTION.getSeconds(), remaining))));
        } catch (RuntimeException exception) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.CASSANDRA, exception);
        }
    }

    public List<Activity> month(UUID owner, YearMonth month) {
        try {
            List<Row> rows =
                    session.execute(
                                    select.bind(owner, month.toString(), MAX_MONTH_EVENTS + 1)
                                            .setPageSize(MAX_MONTH_EVENTS + 1))
                            .all();
            if (rows.size() > MAX_MONTH_EVENTS) {
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_LIMIT);
            }
            return rows.stream()
                    .map(
                            row ->
                                    new Activity(
                                            row.getUuid("event_id"),
                                            AnalyticsEventType.valueOf(row.getString("event_type")),
                                            row.getLong("amount_minor"),
                                            row.getInstant("occurred_at")))
                    .toList();
        } catch (AnalyticsUnavailable exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.CASSANDRA, exception);
        }
    }

    static YearMonth month(Instant instant) {
        return YearMonth.from(instant.atOffset(ZoneOffset.UTC));
    }

    public record Activity(
            UUID eventId, AnalyticsEventType type, long amountMinor, Instant occurredAt) {}
}
