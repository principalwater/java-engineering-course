package dev.principalwater.bank.analytics.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.model.AnalyticsEventType;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.InvalidAnalyticsRecord;
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
    // Небольшой scan не создаёт очередь задач при долгой недоступности брокера.
    private static final int BATCH_SIZE = 16;
    private static final int LEASE_SECONDS = 30;
    private static final int REPUBLISH_SECONDS = 30;
    private static final int RETRY_STEP_SECONDS = 2;
    private static final int MAX_RETRY_SECONDS = 60;
    private static final int MAX_RECORDED_ATTEMPTS = 1_000_000;
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
        // Просмотр не меняет деньги: оба нуля — признаки UI-события, а не остаток счёта.
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
        // Повтор после потери HTTP-ответа сохраняет исходную запись; ниже проверяем коллизию
        // payload.
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
        // Время просмотра назначает сервер: повтор сохраняет момент первого принятия.
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
                UPDATE analytics_inbox SET lease_token = ?, lease_until = now() + make_interval(secs => ?)
                WHERE event_id = ? AND NOT (cassandra_done AND warehouse_done) AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now()) RETURNING payload::text
                """,
                        (row, number) -> new Delivery(decode(row.getString(1)), lease),
                        lease,
                        LEASE_SECONDS,
                        eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean projectionPending(BankEvent event, AnalyticsProjection projection) {
        List<ProjectionState> rows =
                jdbc.query(
                        "SELECT payload::text, cassandra_done, warehouse_done FROM analytics_inbox WHERE event_id = ?",
                        (row, number) ->
                                new ProjectionState(
                                        decode(row.getString(1)),
                                        projection == AnalyticsProjection.PERSONAL
                                                ? row.getBoolean(2)
                                                : row.getBoolean(3)),
                        event.eventId());
        if (rows.isEmpty()) {
            throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.UNKNOWN_EVENT);
        }
        ProjectionState stored = rows.getFirst();
        if (!stored.event().equals(event)) {
            throw new InvalidAnalyticsRecord(InvalidAnalyticsRecord.Reason.PAYLOAD_MISMATCH);
        }
        return !stored.done();
    }

    public void acknowledgeProjection(UUID eventId, AnalyticsProjection projection) {
        // ACK следует за внешней записью; crash между ними повторяет тот же идемпотентный факт.
        jdbc.update(
                "UPDATE analytics_inbox SET cassandra_done = cassandra_done OR ?, "
                        + "warehouse_done = warehouse_done OR ? WHERE event_id = ?",
                projection == AnalyticsProjection.PERSONAL,
                projection == AnalyticsProjection.BUSINESS,
                eventId);
    }

    public void quarantine(
            AnalyticsProjection projection,
            String topic,
            int partition,
            long offset,
            String hash,
            InvalidAnalyticsRecord.Reason reason) {
        // Хеш различает содержимое при повторном использовании offset; сырой payload не
        // сохраняется.
        jdbc.update(
                """
                INSERT INTO analytics_quarantine(projection, topic, partition_id, record_offset, payload_hash, reason, rejected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """,
                projection.name(),
                topic,
                partition,
                offset,
                hash,
                reason.name(),
                Timestamp.from(clock.instant()));
    }

    public void release(Delivery delivery, boolean failed) {
        // Через 30s повторяем неподтверждённый факт даже после broker ACK/retention; сбои отступают
        // до 60s.
        jdbc.update(
                """
                UPDATE analytics_inbox SET lease_token = NULL, lease_until = NULL,
                attempts = least(attempts + 1, ?),
                next_attempt_at = now() + make_interval(secs => CASE WHEN ? THEN least(?, ? + attempts * ?) ELSE ? END)
                WHERE event_id = ? AND lease_token = ?
                """,
                MAX_RECORDED_ATTEMPTS,
                failed,
                MAX_RETRY_SECONDS,
                RETRY_STEP_SECONDS,
                RETRY_STEP_SECONDS,
                REPUBLISH_SECONDS,
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

    public record Delivery(BankEvent event, UUID lease) {}

    private record ProjectionState(BankEvent event, boolean done) {}
}
