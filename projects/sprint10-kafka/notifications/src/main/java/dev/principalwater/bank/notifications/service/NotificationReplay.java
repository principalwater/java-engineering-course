package dev.principalwater.bank.notifications.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.messaging.KafkaOutbox;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationReplay {
    // Оператор восстанавливает историю конечными страницами, не удерживая большую транзакцию.
    private static final int MAX_PAGE_SIZE = 1000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KafkaOutbox outbox;

    public NotificationReplay(JdbcTemplate jdbc, ObjectMapper mapper, KafkaOutbox outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    @Transactional
    public Page requeue(long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid receipt replay page");
        List<Row> rows =
                jdbc.query(
                        "SELECT sequence,caller,payload::text FROM bank_notification WHERE sequence>? ORDER BY sequence LIMIT ?",
                        (rs, n) ->
                                new Row(
                                        rs.getLong(1),
                                        source(rs.getString(2)),
                                        event(rs.getString(3))),
                        after,
                        limit);
        for (Row row : rows) {
            String identity = row.source().identity(row.event().eventId());
            outbox.enqueue(
                    BankTopics.NOTIFICATION_RECEIPTS,
                    row.event().ownerId().toString(),
                    identity,
                    new NotificationReceipt(row.source(), row.event()));
            // Только подтверждённые публикации переоткрываются; уже leased PENDING не
            // перехватывается.
            jdbc.update(
                    "UPDATE bank_kafka_outbox SET status='PENDING',next_attempt_at=now() WHERE topic=? AND message_id=? AND status='PUBLISHED'",
                    BankTopics.NOTIFICATION_RECEIPTS,
                    identity);
        }
        return new Page(rows.size(), rows.isEmpty() ? after : rows.getLast().sequence());
    }

    private NotificationSource source(String caller) {
        for (NotificationSource source : NotificationSource.values())
            if (source.getCaller().equals(caller)) return source;
        throw new IllegalStateException("Unknown stored notification source");
    }

    private BankEvent event(String payload) {
        try {
            return mapper.readValue(payload, BankEvent.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored notification");
        }
    }

    public record Page(int count, long nextSequence) {}

    private record Row(long sequence, NotificationSource source, BankEvent event) {}
}
