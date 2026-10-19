package dev.principalwater.bank.accounts.dao;

import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationDao {
    private final JdbcTemplate jdbc;

    public OperationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserve(UUID owner, InternalOperation command, Instant now) {
        // Повтор сохраняет первую операцию; replay сверяет payload и возвращает исходный результат.
        return jdbc.update(
                        """
                INSERT INTO money_operation(owner_id,type,request_id,amount_minor,recipient_id,status,occurred_at)
                VALUES (?,?,?,?,?,'PENDING',?) ON CONFLICT DO NOTHING
                """,
                        owner,
                        command.type().name(),
                        command.operationId(),
                        command.amountMinor(),
                        command.recipientId(),
                        java.sql.Timestamp.from(now))
                == 1;
    }

    public OperationResult replay(UUID owner, InternalOperation command) {
        return jdbc.queryForObject(
                "SELECT * FROM money_operation WHERE owner_id=? AND type=? AND request_id=?",
                (rs, row) -> {
                    if (rs.getLong("amount_minor") != command.amountMinor()
                            || !Objects.equals(
                                    rs.getObject("recipient_id", UUID.class),
                                    command.recipientId())) {
                        throw new BankException(BankError.OPERATION_CONFLICT);
                    }
                    return new OperationResult(
                            command.operationId(),
                            OperationStatus.valueOf(rs.getString("status")),
                            rs.getString("error"),
                            owner,
                            rs.getObject("balance_minor", Long.class),
                            rs.getTimestamp("occurred_at").toInstant());
                },
                owner,
                command.type().name(),
                command.operationId());
    }

    public OperationResult finish(
            UUID owner, InternalOperation command, BankError rejection, long balance, Instant now) {
        OperationStatus status =
                rejection == null ? OperationStatus.SUCCEEDED : OperationStatus.REJECTED;
        String error = rejection == null ? null : rejection.name();
        jdbc.update(
                """
                UPDATE money_operation SET status=?,error=?,balance_minor=?,occurred_at=?
                WHERE owner_id=? AND type=? AND request_id=?
                """,
                status.name(),
                error,
                balance,
                java.sql.Timestamp.from(now),
                owner,
                command.type().name(),
                command.operationId());
        return new OperationResult(command.operationId(), status, error, owner, balance, now);
    }
}
