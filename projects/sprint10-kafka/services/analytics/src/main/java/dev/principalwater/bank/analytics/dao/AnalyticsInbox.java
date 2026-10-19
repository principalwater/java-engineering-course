package dev.principalwater.bank.analytics.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsRecord;
import dev.principalwater.bank.analytics.model.InvalidAnalyticsRecord;
import dev.principalwater.bank.contract.AnalyticsEventType;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.UiActivityEvent;
import dev.principalwater.bank.contract.UiActivityRequest;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class AnalyticsInbox {
    // Небольшой scan не создаёт очередь задач при долгой недоступности брокера.
    private static final int BATCH_SIZE = 16;
    private static final int LEASE_SECONDS = 30;
    private static final int REPUBLISH_SECONDS = 30;
    private static final int MAX_REPUBLISH_SECONDS = 900;
    // 30 * 2^5 уже достигает предела 15 минут; bounded shift исключает overflow на старом backlog.
    private static final int MAX_REPUBLISH_SHIFT = 5;
    private static final int RETRY_STEP_SECONDS = 2;
    private static final int MAX_RETRY_SECONDS = 60;
    private static final int MAX_RECORDED_ATTEMPTS = 1_000_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ActivityExportQueue exports;

    public AnalyticsInbox(
            JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, ActivityExportQueue exports) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.exports = exports;
    }

    @Transactional
    public void acceptBackend(BankEvent event) {
        AnalyticsEventType.validateBackend(event, clock.instant());
        accept(new AnalyticsRecord(event, null), false);
    }

    @Transactional
    public void acceptView(UUID eventId, UUID ownerId) {
        // Просмотр не меняет деньги: оба нуля — признаки UI-события, а не остаток счёта.
        accept(
                new AnalyticsRecord(
                        new BankEvent(
                                uiIdentity(eventId, ownerId, "CABINET_VIEW"),
                                ownerId,
                                AnalyticsEventType.CABINET_VIEW.name(),
                                0,
                                0,
                                clock.instant()),
                        null),
                true);
    }

    @Transactional
    public void acceptUi(UiActivityRequest request, UUID ownerId) {
        request.validate();
        accept(
                new AnalyticsRecord(
                        null,
                        new UiActivityEvent(
                                uiIdentity(request.eventId(), ownerId, "TYPED_UI"),
                                ownerId,
                                request.type(),
                                request.section(),
                                clock.instant())),
                true);
    }

    private UUID uiIdentity(UUID clientId, UUID ownerId, String namespace) {
        // Пользовательский UUID не занимает пространство доверенных ledger events. Owner и
        // endpoint namespace сохраняют replay, но не дают заранее захватить чужой финансовый ID.
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(
                    ("bank.analytics.ui.v1\0" + namespace + "\0").getBytes(StandardCharsets.UTF_8));
            byte[] key =
                    ByteBuffer.allocate(32)
                            .putLong(ownerId.getMostSignificantBits())
                            .putLong(ownerId.getLeastSignificantBits())
                            .putLong(clientId.getMostSignificantBits())
                            .putLong(clientId.getLeastSignificantBits())
                            .array();
            byte[] bytes = hash.digest(key);
            // UUIDv8 обозначает прикладную SHA-256 схему; используются первые 128 бит digest.
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer identity = ByteBuffer.wrap(bytes);
            return new UUID(identity.getLong(), identity.getLong());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private void accept(AnalyticsRecord event, boolean serverTime) {
        // Повтор после потери HTTP-ответа сохраняет исходную запись; ниже проверяем коллизию
        // payload.
        int inserted =
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
        if (inserted > 0 && serverTime) reserveUiMonth(event);
        AnalyticsRecord stored =
                jdbc.queryForObject(
                        "SELECT payload::text FROM analytics_inbox WHERE event_id = ?",
                        (row, number) -> decode(row.getString(1)),
                        event.eventId());
        // Время просмотра назначает сервер: повтор сохраняет момент первого принятия.
        boolean sameView =
                serverTime
                                && stored != null
                                && stored.ownerId().equals(event.ownerId())
                                && event.type().equals(AnalyticsEventType.CABINET_VIEW.name())
                                && stored.type().equals(AnalyticsEventType.CABINET_VIEW.name())
                        || serverTime
                                && stored != null
                                && event.ui() != null
                                && stored.ui() != null
                                && stored.ui().sameAction(event.ui());
        if (!sameView && !event.equals(stored)) {
            throw new BankException(BankError.OPERATION_CONFLICT);
        }
    }

    private void reserveUiMonth(AnalyticsRecord event) {
        // Оба пользовательских HTTP-входа делят месячный лимит в той же PG transaction, что и
        // inbox.
        // Новый UUID расходует место один раз; replay и отклонённый запрос не увеличивают счётчик.
        int reserved =
                jdbc.update(
                        """
                INSERT INTO analytics_ui_months(owner_id, month, accepted) VALUES (?, ?, 1)
                ON CONFLICT (owner_id, month) DO UPDATE SET accepted = analytics_ui_months.accepted + 1
                WHERE analytics_ui_months.accepted < ?
                """,
                        event.ownerId(),
                        CassandraActivityStore.month(event.occurredAt()).toString(),
                        CassandraActivityStore.MAX_MONTH_EVENTS);
        if (reserved == 0)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "UI activity monthly limit reached");
    }

    public List<UUID> pendingIds() {
        return jdbc.query(
                """
                SELECT event_id FROM analytics_inbox
                WHERE NOT (cassandra_done AND warehouse_done) AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now())
                ORDER BY (attempts > 0), next_attempt_at, event_id LIMIT ?
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

    public boolean projectionPending(AnalyticsRecord event, AnalyticsProjection projection) {
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

    @Transactional
    public void acknowledgeProjection(UUID eventId, AnalyticsProjection projection) {
        // Внешняя Cassandra уже записана; её ACK и dirty revision фиксируются одной PG transaction.
        if (projection == AnalyticsProjection.PERSONAL) exports.acknowledgePersonal(eventId);
        else
            jdbc.update(
                    "UPDATE analytics_inbox SET warehouse_done = true WHERE event_id = ?", eventId);
    }

    public void quarantine(
            String projection,
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
                projection,
                topic,
                partition,
                offset,
                hash,
                reason.name(),
                Timestamp.from(clock.instant()));
    }

    public void release(Delivery delivery, boolean failed) {
        // Успешный publish без sink ACK отступает от 30s до 15min, чтобы outage одной витрины не
        // вытеснял свежие факты дублями. Сбой брокера повторяем раньше, с пределом 60s.
        jdbc.update(
                """
                UPDATE analytics_inbox SET lease_token = NULL, lease_until = NULL,
                attempts = least(attempts + 1, ?),
                next_attempt_at = now() + make_interval(secs => CASE WHEN ? THEN least(?, ? + attempts * ?)
                    ELSE least(?, ? * (1 << least(attempts, ?))) END)
                WHERE event_id = ? AND lease_token = ?
                """,
                MAX_RECORDED_ATTEMPTS,
                failed,
                MAX_RETRY_SECONDS,
                RETRY_STEP_SECONDS,
                RETRY_STEP_SECONDS,
                MAX_REPUBLISH_SECONDS,
                REPUBLISH_SECONDS,
                MAX_REPUBLISH_SHIFT,
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
        // Эта HTTP-сводка читает event-stream mart: UI snapshot ждёт Cassandra отдельно и не
        // должен скрывать уже подтверждённую независимую финансовую CH-проекцию.
        Boolean pending =
                jdbc.queryForObject(
                        """
                SELECT EXISTS(SELECT 1 FROM analytics_inbox WHERE occurred_at >= ? AND occurred_at < ?
                AND NOT warehouse_done AND event_type NOT IN ('SCREEN_OPENED','FORM_OPENED'))
                """,
                        Boolean.class,
                        Timestamp.from(start),
                        Timestamp.from(end));
        return Boolean.TRUE.equals(pending);
    }

    private String encode(AnalyticsRecord event) {
        try {
            return event.encode(mapper);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid analytics event", exception);
        }
    }

    private AnalyticsRecord decode(String payload) {
        try {
            return AnalyticsRecord.decode(payload, mapper);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored analytics event", exception);
        }
    }

    public record Delivery(AnalyticsRecord event, UUID lease) {}

    private record ProjectionState(AnalyticsRecord event, boolean done) {}
}
