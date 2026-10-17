package dev.principalwater.bank.runtime.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.error.BankException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class RestOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(RestOutbox.class);
    private static final int BATCH_SIZE = 16;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ServiceClient client;

    public RestOutbox(JdbcTemplate jdbc, ObjectMapper mapper, ServiceClient client) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.client = client;
    }

    // JdbcTemplate использует локальную транзакцию владельца вместе с его business-записью.
    public void enqueue(String target, String path, BankEvent event) {
        jdbc.update(
                """
                INSERT INTO bank_outbox(id, event_id, target, path, payload)
                VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (target, event_id) DO NOTHING
                """,
                UUID.randomUUID(),
                event.eventId(),
                target,
                path,
                encode(event));
    }

    public List<UUID> pendingIds() {
        List<UUID> due =
                jdbc.query(
                        """
                SELECT id FROM bank_outbox WHERE status = 'PENDING' AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now()) ORDER BY next_attempt_at, id LIMIT ?
                """,
                        (row, number) -> row.getObject("id", UUID.class),
                        BATCH_SIZE);
        return due;
    }

    public void deliver(UUID id) {
        UUID lease = UUID.randomUUID();
        List<Delivery> claimed =
                jdbc.query(
                        """
                UPDATE bank_outbox SET lease_token = ?, lease_until = now() + interval '30 seconds'
                WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now()) RETURNING target, path, payload::text
                """,
                        (row, number) ->
                                new Delivery(
                                        row.getString("target"),
                                        row.getString("path"),
                                        decode(row.getString("payload"))),
                        lease,
                        id);
        if (claimed.isEmpty()) {
            return;
        }
        Delivery delivery = claimed.getFirst();
        try {
            client.post(delivery.target(), delivery.path(), delivery.event(), Void.class);
            finish(id, lease, "DELIVERED");
        } catch (BankException exception) {
            finish(id, lease, "REJECTED");
            LOG.warn("Outbox delivery rejected: {}", exception.getError().name());
        } catch (RemoteUnavailable exception) {
            jdbc.update(
                    """
                    UPDATE bank_outbox SET lease_token = NULL, lease_until = NULL,
                    next_attempt_at = now() + interval '5 seconds', attempts = attempts + 1
                    WHERE id = ? AND lease_token = ? AND status = 'PENDING'
                    """,
                    id,
                    lease);
        }
    }

    private void finish(UUID id, UUID lease, String status) {
        jdbc.update(
                """
                UPDATE bank_outbox SET status = ?, lease_token = NULL, lease_until = NULL
                WHERE id = ? AND lease_token = ? AND status = 'PENDING'
                """,
                status,
                id,
                lease);
    }

    private String encode(BankEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid bank event");
        }
    }

    private BankEvent decode(String json) {
        try {
            return mapper.readValue(json, BankEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored bank event");
        }
    }

    private record Delivery(String target, String path, BankEvent event) {}
}
