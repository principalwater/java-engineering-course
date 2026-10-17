package dev.principalwater.bank.accounts.dao;

import dev.principalwater.bank.accounts.model.Account;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.HistoryEntry;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.contract.Recipient;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDao {
    private final JdbcTemplate jdbc;

    public AccountDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account provision(Actor actor) {
        jdbc.update(
                "INSERT INTO account(id,issuer,subject,login) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(),
                actor.issuer(),
                actor.subject(),
                actor.login());
        return jdbc
                .query(
                        "SELECT * FROM account WHERE issuer=? AND subject=?",
                        this::account,
                        actor.issuer(),
                        actor.subject())
                .stream()
                .findFirst()
                .orElseThrow(() -> new BankException(BankError.IDENTITY_CONFLICT));
    }

    public Optional<Account> lock(UUID id) {
        // ID неизменяем: NO KEY UPDATE совместим с KEY SHARE внешних ключей журнала операций.
        return jdbc
                .query("SELECT * FROM account WHERE id=? FOR NO KEY UPDATE", this::account, id)
                .stream()
                .findFirst();
    }

    public boolean updateProfile(UUID id, String name, LocalDate birthDate, long version) {
        return jdbc.update(
                        "UPDATE account SET full_name=?,birth_date=?,version=version+1 WHERE id=? AND version=?",
                        name,
                        birthDate,
                        id,
                        version)
                == 1;
    }

    public void balance(UUID id, long balance) {
        if (jdbc.update("UPDATE account SET balance_minor=? WHERE id=?", balance, id) != 1) {
            throw new IllegalStateException("Locked account disappeared");
        }
    }

    public Page<Recipient> recipients(UUID owner, String after, String search, int limit) {
        checkLimit(limit);
        if (search.length() > 80) {
            throw new BankException(BankError.INVALID_REQUEST);
        }
        UUID cursor = after == null ? new UUID(0, 0) : UUID.fromString(after);
        // Параметры LIKE экранируются как текст, а не превращаются в пользовательский шаблон.
        String query = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Recipient> rows =
                jdbc.query(
                        """
                SELECT id,login,full_name FROM account
                WHERE id<>? AND id>? AND birth_date IS NOT NULL AND full_name<>''
                  AND (full_name ILIKE ? ESCAPE '!' OR login ILIKE ? ESCAPE '!')
                ORDER BY id LIMIT ?
                """,
                        (rs, n) ->
                                new Recipient(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("login"),
                                        rs.getString("full_name")),
                        owner,
                        cursor,
                        query,
                        query,
                        limit + 1);
        boolean more = rows.size() > limit;
        List<Recipient> page = rows.subList(0, Math.min(rows.size(), limit));
        return new Page<>(page, more ? page.getLast().accountId().toString() : null);
    }

    public Page<HistoryEntry> history(UUID owner, String before, int limit) {
        checkLimit(limit);
        long cursor = before == null ? Long.MAX_VALUE : Long.parseLong(before);
        if (cursor <= 0) {
            throw new BankException(BankError.INVALID_REQUEST);
        }
        List<HistoryRow> rows =
                jdbc.query(
                        """
                SELECT l.*, a.full_name AS counterparty FROM ledger_entry l
                LEFT JOIN account a ON a.id=l.counterparty_id
                WHERE l.owner_id=? AND l.sequence<? ORDER BY l.sequence DESC LIMIT ?
                """,
                        (rs, n) ->
                                new HistoryRow(
                                        rs.getLong("sequence"),
                                        new HistoryEntry(
                                                rs.getObject("request_id", UUID.class),
                                                rs.getString("type"),
                                                rs.getLong("amount_minor"),
                                                rs.getLong("balance_minor"),
                                                OperationStatus.SUCCEEDED,
                                                rs.getTimestamp("occurred_at").toInstant(),
                                                rs.getString("counterparty"))),
                        owner,
                        cursor,
                        limit + 1);
        boolean more = rows.size() > limit;
        List<HistoryRow> page = rows.subList(0, Math.min(rows.size(), limit));
        return new Page<>(
                page.stream().map(HistoryRow::entry).toList(),
                more ? Long.toString(page.getLast().sequence()) : null);
    }

    public void ledger(
            UUID owner,
            UUID commandOwner,
            String operationType,
            UUID requestId,
            String type,
            long amount,
            long balance,
            UUID counterparty,
            Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO ledger_entry(owner_id,command_owner_id,operation_type,request_id,type,
                                         amount_minor,balance_minor,counterparty_id,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                owner,
                commandOwner,
                operationType,
                requestId,
                type,
                amount,
                balance,
                counterparty,
                java.sql.Timestamp.from(occurredAt));
    }

    private Account account(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("issuer"),
                rs.getString("subject"),
                rs.getString("login"),
                rs.getString("full_name"),
                rs.getObject("birth_date", LocalDate.class),
                rs.getLong("balance_minor"),
                rs.getLong("version"));
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > BankLimits.MAX_PAGE_SIZE) {
            throw new BankException(BankError.INVALID_REQUEST);
        }
    }

    private record HistoryRow(long sequence, HistoryEntry entry) {}
}
