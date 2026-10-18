package dev.principalwater.bank.notifications.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.NotificationView;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationDao {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotificationDao(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Page<NotificationView> inbox(UUID owner, Long before, int limit) {
        if (limit < 1 || limit > BankLimits.MAX_PAGE_SIZE || before != null && before <= 0) {
            throw new BankException(BankError.INVALID_REQUEST);
        }
        List<NotificationRow> rows =
                jdbc.query(
                        """
                SELECT sequence, payload::text FROM bank_notification
                WHERE owner_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?
                """,
                        (rs, n) ->
                                new NotificationRow(
                                        rs.getLong("sequence"), view(rs.getString("payload"))),
                        owner,
                        before == null ? Long.MAX_VALUE : before,
                        limit + 1);
        List<NotificationRow> page = rows.subList(0, Math.min(rows.size(), limit));
        return new Page<>(
                page.stream().map(NotificationRow::view).toList(),
                rows.size() > limit ? Long.toString(page.getLast().sequence()) : null);
    }

    private NotificationView view(String payload) {
        try {
            BankEvent event = mapper.readValue(payload, BankEvent.class);
            return new NotificationView(
                    event.eventId(),
                    event.type(),
                    event.amountMinor(),
                    event.balanceMinor(),
                    event.occurredAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored notification");
        }
    }

    private record NotificationRow(long sequence, NotificationView view) {}

    public boolean receive(String caller, BankEvent event) {
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid notification payload");
        }
        // Конкурентный повтор сохраняет первый receipt; ниже сверяем payload перед ответом.
        int inserted =
                jdbc.update(
                        """
                INSERT INTO bank_notification(caller, event_id, payload) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (caller, event_id) DO NOTHING
                """,
                        caller,
                        event.eventId(),
                        payload);
        Boolean same =
                jdbc.queryForObject(
                        """
                SELECT payload = ?::jsonb FROM bank_notification WHERE caller = ? AND event_id = ?
                """,
                        Boolean.class,
                        payload,
                        caller,
                        event.eventId());
        if (!Boolean.TRUE.equals(same)) {
            throw new BankException(BankError.OPERATION_CONFLICT);
        }
        return inserted == 1;
    }
}
