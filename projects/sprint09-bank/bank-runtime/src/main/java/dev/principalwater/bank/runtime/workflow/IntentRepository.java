package dev.principalwater.bank.runtime.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class IntentRepository {
    private static final int BATCH_SIZE = 16;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IntentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Intent reserve(InternalOperation operation) {
        jdbc.update(
                """
                INSERT INTO bank_intent(id, issuer, subject, operation_type, request_id, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (issuer, subject, operation_type, request_id) DO NOTHING
                """,
                UUID.randomUUID(),
                operation.actor().issuer(),
                operation.actor().subject(),
                operation.type().name(),
                operation.operationId(),
                encode(operation));
        Intent intent =
                jdbc.queryForObject(
                        """
                SELECT id, payload::text, result::text, lease_token FROM bank_intent
                WHERE issuer = ? AND subject = ? AND operation_type = ? AND request_id = ?
                """,
                        this::read,
                        operation.actor().issuer(),
                        operation.actor().subject(),
                        operation.type().name(),
                        operation.operationId());
        // Ключ уже содержит issuer/sub/type/requestId; изменяемый login не меняет команду.
        if (intent == null
                || !Objects.equals(intent.operation().amountMinor(), operation.amountMinor())
                || !Objects.equals(intent.operation().recipientId(), operation.recipientId())) {
            throw new BankException(BankError.OPERATION_CONFLICT);
        }
        return intent;
    }

    public Intent find(UUID id) {
        return jdbc.queryForObject(
                "SELECT id, payload::text, result::text, lease_token FROM bank_intent WHERE id = ?",
                this::read,
                id);
    }

    public List<UUID> due() {
        return jdbc.query(
                """
                SELECT id FROM bank_intent WHERE result IS NULL AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now()) ORDER BY next_attempt_at, id LIMIT ?
                """,
                (row, number) -> row.getObject("id", UUID.class),
                BATCH_SIZE);
    }

    public Intent claim(UUID id) {
        List<Intent> claimed =
                jdbc.query(
                        """
                UPDATE bank_intent SET lease_token = ?, lease_until = now() + interval '30 seconds'
                WHERE id = ? AND result IS NULL AND next_attempt_at <= now()
                AND (lease_until IS NULL OR lease_until < now())
                RETURNING id, payload::text, result::text, lease_token
                """,
                        this::read,
                        UUID.randomUUID(),
                        id);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    public boolean complete(Intent intent, OperationResult result) {
        return jdbc.update(
                        """
                UPDATE bank_intent SET result = ?::jsonb, lease_token = NULL, lease_until = NULL
                WHERE id = ? AND lease_token = ? AND result IS NULL
                """,
                        encode(result),
                        intent.id(),
                        intent.lease())
                == 1;
    }

    public void defer(Intent intent) {
        jdbc.update(
                """
                UPDATE bank_intent SET lease_token = NULL, lease_until = NULL,
                next_attempt_at = now() + interval '5 seconds', attempts = attempts + 1
                WHERE id = ? AND lease_token = ? AND result IS NULL
                """,
                intent.id(),
                intent.lease());
    }

    private Intent read(ResultSet row, int number) throws SQLException {
        String result = row.getString("result");
        return new Intent(
                row.getObject("id", UUID.class),
                decode(row.getString("payload"), InternalOperation.class),
                result == null ? null : decode(result, OperationResult.class),
                row.getObject("lease_token", UUID.class));
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid operation payload");
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored operation");
        }
    }

    public record Intent(
            UUID id, InternalOperation operation, OperationResult result, UUID lease) {}
}
