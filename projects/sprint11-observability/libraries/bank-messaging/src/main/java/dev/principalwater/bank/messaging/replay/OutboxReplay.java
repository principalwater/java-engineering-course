package dev.principalwater.bank.messaging.replay;

import dev.principalwater.bank.contract.BankTopics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxReplay {
    // Конечная страница ограничивает время блокировок; продолжение задаётся сохранённым sequence.
    private static final int MAX_PAGE_SIZE = 1000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public OutboxReplay(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public Page requeue(String application, long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid outbox replay page");
        List<String> topics =
                switch (application) {
                    case "accounts" ->
                            List.of(
                                    BankTopics.ACCOUNTS_NOTIFICATIONS,
                                    BankTopics.ACCOUNTS_ANALYTICS);
                    case "cash" -> List.of(BankTopics.CASH_NOTIFICATIONS);
                    case "transfer" -> List.of(BankTopics.TRANSFER_NOTIFICATIONS);
                    case "notifications" -> List.of(BankTopics.NOTIFICATION_RECEIPTS);
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported outbox replay application");
                };
        return transaction.execute(
                status -> {
                    List<Object> parameters = new ArrayList<>(topics);
                    parameters.add(after);
                    parameters.add(limit);
                    String markers = String.join(",", Collections.nCopies(topics.size(), "?"));
                    List<Row> rows =
                            jdbc.query(
                                    "SELECT id,sequence FROM bank_kafka_outbox WHERE topic IN ("
                                            + markers
                                            + ") AND sequence>? ORDER BY sequence LIMIT ?",
                                    (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getLong(2)),
                                    parameters.toArray());
                    int requeued = 0;
                    for (Row row : rows) {
                        // Повторная публикация сохраняет содержимое, ключ, ID и текущую аренду
                        // записи.
                        requeued +=
                                jdbc.update(
                                        "UPDATE bank_kafka_outbox SET status='PENDING',next_attempt_at=now() WHERE id=? AND status='PUBLISHED'",
                                        row.id());
                    }
                    return new Page(
                            rows.size(),
                            requeued,
                            rows.isEmpty() ? after : rows.getLast().sequence());
                });
    }

    public record Page(int count, int requeued, long nextSequence) {}

    private record Row(UUID id, long sequence) {}
}
