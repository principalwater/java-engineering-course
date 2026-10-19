package dev.principalwater.bank.analytics.dao;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ActivityExportQueue {
    // Один exporter ограничен двумя CQL partitions и одним HTTP insert; 60s lease покрывает
    // конечные I/O deadlines.
    private static final int LEASE_SECONDS = 60;
    // Backoff 2..60s удерживает конечную нагрузку при outage; счётчик насыщается до SQL overflow.
    private static final int RETRY_STEP_SECONDS = 2;
    private static final int MAX_RETRY_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 1_000_000;
    private final JdbcTemplate jdbc;

    public ActivityExportQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acknowledgePersonal(UUID eventId) {
        List<Export> owners =
                jdbc.query(
                        """
                SELECT owner_id, to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM') FROM analytics_inbox WHERE event_id = ?
                """,
                        (row, number) ->
                                new Export(
                                        row.getObject(1, UUID.class),
                                        YearMonth.parse(row.getString(2)),
                                        0,
                                        null),
                        eventId);
        if (owners.isEmpty()) return;
        Export owner = owners.getFirst();
        jdbc.update(
                "INSERT INTO analytics_activity_exports(owner_id, month, revision) VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
                owner.ownerId(),
                owner.month().toString());
        // Все ACK и complete берут owner/month до event rows: одинаковый порядок исключает взаимный
        // deadlock.
        jdbc.queryForObject(
                "SELECT revision FROM analytics_activity_exports WHERE owner_id = ? AND month = ? FOR UPDATE",
                Long.class,
                owner.ownerId(),
                owner.month().toString());
        List<Long> revisions =
                jdbc.query(
                        """
                UPDATE analytics_inbox SET cassandra_done = true, activity_revision = nextval('analytics_activity_revision')
                WHERE event_id = ? AND NOT cassandra_done RETURNING activity_revision
                """,
                        (row, number) -> row.getLong(1),
                        eventId);
        if (!revisions.isEmpty())
            jdbc.update(
                    """
                UPDATE analytics_activity_exports SET revision = ?, next_attempt_at = now(), attempts = 0
                WHERE owner_id = ? AND month = ?
                """,
                    revisions.getFirst(),
                    owner.ownerId(),
                    owner.month().toString());
    }

    @Transactional
    public Export claim() {
        UUID lease = UUID.randomUUID();
        List<Export> result =
                jdbc.query(
                        """
                UPDATE analytics_activity_exports SET lease_token = ?, lease_until = now() + make_interval(secs => ?)
                WHERE (owner_id, month) IN (
                    SELECT owner_id, month FROM analytics_activity_exports
                    WHERE exported_revision < revision AND next_attempt_at <= now()
                    AND (lease_until IS NULL OR lease_until < now())
                    ORDER BY next_attempt_at, owner_id, month FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING owner_id, month, revision
                """,
                        (row, number) ->
                                new Export(
                                        row.getObject(1, UUID.class),
                                        YearMonth.parse(row.getString(2)),
                                        row.getLong(3),
                                        lease),
                        lease,
                        LEASE_SECONDS);
        return result.isEmpty() ? null : result.getFirst();
    }

    @Transactional
    public void complete(Export export) {
        int completed =
                jdbc.update(
                        """
                UPDATE analytics_activity_exports SET exported_revision = ?, lease_token = NULL, lease_until = NULL
                WHERE owner_id = ? AND month = ? AND revision = ? AND lease_token = ?
                """,
                        export.revision(),
                        export.ownerId(),
                        export.month().toString(),
                        export.revision(),
                        export.lease());
        if (completed == 1) {
            // Только durable batch со всё ещё актуальной revision завершает UI warehouse ACK.
            jdbc.update(
                    """
                    UPDATE analytics_inbox SET warehouse_done = true WHERE owner_id = ?
                    AND occurred_at >= ? AND occurred_at < ? AND cassandra_done AND activity_revision <= ?
                    AND event_type IN ('SCREEN_OPENED','FORM_OPENED')
                    """,
                    export.ownerId(),
                    java.sql.Timestamp.from(
                            export.month().atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                    java.sql.Timestamp.from(
                            export.month()
                                    .plusMonths(1)
                                    .atDay(1)
                                    .atStartOfDay(ZoneOffset.UTC)
                                    .toInstant()),
                    export.revision());
        } else {
            jdbc.update(
                    "UPDATE analytics_activity_exports SET lease_token = NULL, lease_until = NULL WHERE owner_id = ? AND month = ? AND lease_token = ?",
                    export.ownerId(),
                    export.month().toString(),
                    export.lease());
        }
    }

    public void fail(Export export) {
        jdbc.update(
                """
                UPDATE analytics_activity_exports SET lease_token = NULL, lease_until = NULL,
                    attempts = least(attempts + 1, ?), next_attempt_at = now() + make_interval(secs => least(?, ? + attempts * ?))
                WHERE owner_id = ? AND month = ? AND lease_token = ?
                """,
                MAX_ATTEMPTS,
                MAX_RETRY_SECONDS,
                RETRY_STEP_SECONDS,
                RETRY_STEP_SECONDS,
                export.ownerId(),
                export.month().toString(),
                export.lease());
    }

    public record Export(UUID ownerId, YearMonth month, long revision, UUID lease) {}
}
