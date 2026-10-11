package dev.principalwater.study.docker;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MovieRepository {
    private final JdbcTemplate jdbc;

    public MovieRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Movie> findAll() {
        return jdbc.query("SELECT id, name, type, release_year FROM movies ORDER BY id",
                (row, index) -> new Movie(row.getInt("id"), row.getString("name"),
                        row.getString("type"), row.getInt("release_year")));
    }
}
