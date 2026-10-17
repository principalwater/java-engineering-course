package dev.principalwater.bank.analytics.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.model.AnalyticsEventType;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnalyticsInbox {
    static final int BATCH_SIZE = 16;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AnalyticsInbox(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void acceptFinancial(BankEvent event) {
        AnalyticsEventType.validateFinancial(event, clock.instant());
        accept(event, false);
    }

    @Transactional
    public void acceptView(UUID eventId, UUID ownerId) {
        accept(
                new BankEvent(
                        eventId,
                        ownerId,
                        AnalyticsEventType.CABINET_VIEW.name(),
                        0,
                        0,
                        clock.instant()),
                true);
    }

    private void accept(BankEvent event, boolean view) {
        jdbc.update(
                """
                INSERT INTO analytics_inbox(event_id, owner_id, event_type, occurred_at, payload, accepted_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                event.ownerId(),
                event.type(),
                Timestamp.from(event.occurredAt()),
                encode(event),
                Timestamp.from(clock.instant()));
        BankEvent stored =
                jdbc.queryForObject(
                        "SELECT payload::text FROM analytics_inbox WHERE event_id = ?",
                        (row, number) -> decode(row.getString(1)),
                        event.eventId());
        boolean sameView =
                view
                        && stored != null
                        && stored.ownerId().equals(event.ownerId())
                        && stored.type().equals(AnalyticsEventType.CABINET_VIEW.name());
        if (!sameView && !event.equals(stored)) {
            throw new BankException(BankError.OPERATION_CONFLICT);
        }
    }

    public List<UUID> pendingIds() {
        return jdbc.query(
                """
                SELECT event_id FROM analytics_inbox
                WHERE NOT (cassandra_done AND warehouse_done) AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now()) ORDER BY next_attempt_at, event_id LIMIT ?
                """,
                (row, number) -> row.getObject(1, UUID.class),
                BATCH_SIZE);
    }

    public Delivery claim(UUID eventId) {
        UUID lease = UUID.randomUUID();
        List<Delivery> rows =
                jdbc.query(
                        """
                UPDATE analytics_inbox SET lease_token = ?, lease_until = now() + interval '30 seconds'
                WHERE event_id = ? AND NOT (cassandra_done AND warehouse_done) AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now()) RETURNING payload::text, cassandra_done, warehouse_done
                """,
                        (row, number) ->
                                new Delivery(
                                        decode(row.getString(1)),
                                        lease,
                                        row.getBoolean(2),
                                        row.getBoolean(3)),
                        lease,
                        eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void acknowledgeCassandra(Delivery delivery) {
        jdbc.update(
                "UPDATE analytics_inbox SET cassandra_done = true WHERE event_id = ? AND lease_token = ?",
                delivery.event().eventId(),
                delivery.lease());
    }

    public void acknowledgeWarehouse(Delivery delivery) {
        jdbc.update(
                "UPDATE analytics_inbox SET warehouse_done = true WHERE event_id = ? AND lease_token = ?",
                delivery.event().eventId(),
                delivery.lease());
    }

    public void release(Delivery delivery, boolean failed) {
        jdbc.update(
                """
                UPDATE analytics_inbox SET lease_token = NULL, lease_until = NULL,
                attempts = least(attempts + 1, 1000000),
                next_attempt_at = now() + make_interval(secs => CASE WHEN ? THEN least(60, 2 + attempts * 2) ELSE 0 END)
                WHERE event_id = ? AND lease_token = ?
                """,
                failed,
                delivery.event().eventId(),
                delivery.lease());
    }

    public boolean customerPending(UUID owner, Instant start, Instant end) {
        Boolean pending =
                jdbc.queryForObject(
                        """
                SELECT EXISTS(SELECT 1 FROM analytics_inbox WHERE owner_id = ? AND occurred_at >= ?
                AND occurred_at < ? AND NOT cassandra_done)
                """,
                        Boolean.class,
                        owner,
                        Timestamp.from(start),
                        Timestamp.from(end));
        return Boolean.TRUE.equals(pending);
    }

    public boolean warehousePending(Instant start, Instant end) {
        Boolean pending =
                jdbc.queryForObject(
                        """
                SELECT EXISTS(SELECT 1 FROM analytics_inbox WHERE occurred_at >= ? AND occurred_at < ?
                AND NOT warehouse_done)
                """,
                        Boolean.class,
                        Timestamp.from(start),
                        Timestamp.from(end));
        return Boolean.TRUE.equals(pending);
    }

    private String encode(BankEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid analytics event", exception);
        }
    }

    private BankEvent decode(String payload) {
        try {
            return mapper.readValue(payload, BankEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored analytics event", exception);
        }
    }

    public record Delivery(
            BankEvent event, UUID lease, boolean cassandraDone, boolean warehouseDone) {}
}
