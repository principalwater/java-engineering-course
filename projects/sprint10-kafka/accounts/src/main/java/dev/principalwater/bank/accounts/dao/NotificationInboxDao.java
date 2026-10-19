package dev.principalwater.bank.accounts.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.contract.NotificationView;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.messaging.MessageRejected;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationInboxDao {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotificationInboxDao(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean accept(NotificationReceipt receipt) {
        BankEvent event = receipt.event();
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new MessageRejected();
        }
        // Один UUID допустим у разных источников; смена владельца или payload внутри источника
        // запрещена.
        int fresh =
                jdbc.update(
                        "INSERT INTO notification_inbox(source,event_id,owner_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT(source,event_id) DO NOTHING",
                        receipt.source().name(),
                        event.eventId(),
                        event.ownerId(),
                        json);
        Boolean same =
                jdbc.queryForObject(
                        "SELECT owner_id=? AND payload=?::jsonb FROM notification_inbox WHERE source=? AND event_id=?",
                        Boolean.class,
                        event.ownerId(),
                        json,
                        receipt.source().name(),
                        event.eventId());
        if (!Boolean.TRUE.equals(same)) throw new MessageRejected();
        return fresh == 1;
    }

    public Page<NotificationView> inbox(UUID owner, Long before, int limit) {
        if (limit < 1 || limit > BankLimits.MAX_PAGE_SIZE || before != null && before <= 0)
            throw new BankException(BankError.INVALID_REQUEST);
        List<Entry> rows =
                jdbc.query(
                        "SELECT sequence,source,payload::text,read_at FROM notification_inbox WHERE owner_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?",
                        (rs, n) -> new Entry(rs.getLong("sequence"), view(rs)),
                        owner,
                        before == null ? Long.MAX_VALUE : before,
                        limit + 1);
        List<Entry> page = rows.subList(0, Math.min(rows.size(), limit));
        return new Page<>(
                page.stream().map(Entry::view).toList(),
                rows.size() > limit ? Long.toString(page.getLast().sequence()) : null);
    }

    public Read read(UUID owner, NotificationSource source, UUID eventId, Instant now) {
        List<NotificationView> changed =
                jdbc.query(
                        "UPDATE notification_inbox SET read_at=? WHERE owner_id=? AND source=? AND event_id=? AND read_at IS NULL RETURNING source,payload::text,read_at",
                        (rs, n) -> view(rs),
                        Timestamp.from(now),
                        owner,
                        source.name(),
                        eventId);
        if (!changed.isEmpty()) return new Read(changed.getFirst(), true);
        List<NotificationView> rows =
                jdbc.query(
                        "SELECT source,payload::text,read_at FROM notification_inbox WHERE owner_id=? AND source=? AND event_id=?",
                        (rs, n) -> view(rs),
                        owner,
                        source.name(),
                        eventId);
        if (rows.isEmpty()) throw new BankException(BankError.NOTIFICATION_NOT_FOUND);
        return new Read(rows.getFirst(), false);
    }

    private NotificationView view(ResultSet row) throws SQLException {
        try {
            BankEvent event = mapper.readValue(row.getString("payload"), BankEvent.class);
            Timestamp read = row.getTimestamp("read_at");
            return new NotificationView(
                    event.eventId(),
                    event.type(),
                    event.amountMinor(),
                    event.balanceMinor(),
                    event.occurredAt(),
                    NotificationSource.valueOf(row.getString("source")),
                    read == null ? null : read.toInstant());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored notification");
        }
    }

    public record Read(NotificationView view, boolean first) {}

    private record Entry(long sequence, NotificationView view) {}
}
