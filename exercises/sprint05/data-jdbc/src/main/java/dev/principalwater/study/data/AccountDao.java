package dev.principalwater.study.data;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDao {
    private static final RowMapper<Account> MAPPER = (row, index) -> new Account(
            row.getLong("id"), row.getString("name"), row.getBigDecimal("balance"));
    private final JdbcTemplate jdbc;

    public AccountDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Account> findAll() {
        return jdbc.query("SELECT id, name, balance FROM account ORDER BY id", MAPPER);
    }

    public Account findFirstByName(String name) {
        return jdbc.queryForObject("SELECT id, name, balance FROM account WHERE name = ? ORDER BY id LIMIT 1",
                MAPPER, name);
    }

    public void create(String name) {
        jdbc.update("INSERT INTO account (name) VALUES (?)", name);
    }

    public void update(Account account) {
        int changed = jdbc.update("UPDATE account SET name = ?, balance = ? WHERE id = ?",
                account.name(), account.balance(), account.id());
        if (changed != 1) throw new IllegalArgumentException("Account not found");
    }

    int adjustBalance(Long id, BigDecimal delta) {
        // Арифметика в БД сохраняет актуальный баланс без read-modify-write устаревшего объекта.
        return jdbc.update("UPDATE account SET balance = balance + ? WHERE id = ?", delta, id);
    }
}
