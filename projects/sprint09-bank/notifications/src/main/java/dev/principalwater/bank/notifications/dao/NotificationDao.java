package dev.principalwater.bank.notifications.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
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

    public boolean receive(String caller, BankEvent event) {
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid notification payload");
        }
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
