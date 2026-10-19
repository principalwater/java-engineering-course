package dev.principalwater.bank.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

// Изолированная схема позволяет проверить upgrade старых данных без пересоздания TestContext.
public final class MigrationFixture implements AutoCloseable {
    private final DataSource source;
    private final String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    private final String[] locations;
    private final Connection connection;
    private final JdbcTemplate jdbc;

    public MigrationFixture(DataSource source, String... locations) throws SQLException {
        this.source = source;
        this.locations = locations;
        new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
        connection = source.getConnection();
        connection.setSchema(schema);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public void migrate(String target) {
        Flyway.configure()
                .dataSource(source)
                .locations(locations)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load()
                .migrate();
    }

    @Override
    public void close() throws SQLException {
        connection.setSchema("public");
        connection.close();
        new JdbcTemplate(source).execute("DROP SCHEMA " + schema + " CASCADE");
    }
}
