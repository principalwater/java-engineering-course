package dev.principalwater.bank.analytics.dao;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.contract.UiActivityEvent;
import dev.principalwater.bank.contract.UiActivitySection;
import dev.principalwater.bank.contract.UiActivityType;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class CassandraUiActivityStore {
    private final CqlSession session;
    private final Clock clock;
    private final PreparedStatement insert;
    private final PreparedStatement select;

    public CassandraUiActivityStore(CqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
        session.execute(
                """
                CREATE TABLE IF NOT EXISTS ui_activity_by_owner_month (
                    owner_id uuid, month text, occurred_at timestamp, event_id uuid, event_type text,
                    section text, PRIMARY KEY ((owner_id, month), occurred_at, event_id))
                WITH CLUSTERING ORDER BY (occurred_at DESC, event_id ASC)
                AND default_time_to_live = 15552000
                """);
        insert =
                session.prepare(
                        """
                INSERT INTO ui_activity_by_owner_month(owner_id, month, occurred_at, event_id, event_type, section)
                VALUES (?, ?, ?, ?, ?, ?) USING TIMESTAMP ? AND TTL ?
                """);
        select =
                session.prepare(
                        """
                SELECT event_id, event_type, section, occurred_at FROM ui_activity_by_owner_month
                WHERE owner_id = ? AND month = ? LIMIT ?
                """);
    }

    public void insert(UiActivityEvent event) {
        long remaining =
                Duration.between(
                                clock.instant(),
                                event.occurredAt().plus(CassandraActivityStore.RETENTION))
                        .getSeconds();
        if (remaining <= 0) return;
        try {
            long micros =
                    Math.addExact(
                            Math.multiplyExact(event.occurredAt().getEpochSecond(), 1_000_000),
                            event.occurredAt().getNano() / 1000);
            session.execute(
                    insert.bind(
                            event.ownerId(),
                            CassandraActivityStore.month(event.occurredAt()).toString(),
                            event.occurredAt(),
                            event.eventId(),
                            event.type().name(),
                            event.section().name(),
                            micros,
                            Math.toIntExact(
                                    Math.min(
                                            CassandraActivityStore.RETENTION.getSeconds(),
                                            remaining))));
        } catch (RuntimeException failure) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.CASSANDRA, failure);
        }
    }

    public List<UiActivityEvent> month(UUID owner, YearMonth month) {
        try {
            List<Row> rows =
                    session.execute(
                                    select.bind(
                                                    owner,
                                                    month.toString(),
                                                    CassandraActivityStore.MAX_MONTH_EVENTS + 1)
                                            .setPageSize(
                                                    CassandraActivityStore.MAX_MONTH_EVENTS + 1))
                            .all();
            if (rows.size() > CassandraActivityStore.MAX_MONTH_EVENTS)
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_LIMIT);
            return rows.stream()
                    .map(
                            row ->
                                    new UiActivityEvent(
                                            row.getUuid("event_id"),
                                            owner,
                                            UiActivityType.valueOf(row.getString("event_type")),
                                            UiActivitySection.valueOf(row.getString("section")),
                                            row.getInstant("occurred_at")))
                    .toList();
        } catch (AnalyticsUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.CASSANDRA, failure);
        }
    }
}
