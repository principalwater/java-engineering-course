package dev.principalwater.study.mvc;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {
    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<User> findAll() {
        return jdbc.query("SELECT id, first_name, last_name, age, active FROM users ORDER BY id",
                (row, index) -> new User(row.getLong("id"), row.getString("first_name"),
                        row.getString("last_name"), row.getInt("age"), row.getBoolean("active")));
    }

    public void save(User user) {
        jdbc.update("INSERT INTO users(first_name, last_name, age, active) VALUES (?, ?, ?, ?)",
                user.firstName(), user.lastName(), user.age(), user.active());
    }

    public int update(long id, User user) {
        return jdbc.update("UPDATE users SET first_name = ?, last_name = ?, age = ?, active = ? WHERE id = ?",
                user.firstName(), user.lastName(), user.age(), user.active(), id);
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM users WHERE id = ?", id);
    }
}
