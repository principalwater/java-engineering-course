package dev.principalwater.blog.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void namedJdbcPoolUsesItsOwnSettingsAndFileCredentials() throws Exception {
        String password = " test password ";
        var environment = new MockEnvironment()
                .withProperty("REPORTING_URL", "jdbc:h2:mem:configuration-tests")
                .withProperty("REPORTING_DRIVER", "org.h2.Driver")
                .withProperty("REPORTING_USER_FILE", secretFile("user", "sa\n"))
                .withProperty("REPORTING_PASSWORD_FILE", secretFile("password", password + "\r\n"))
                .withProperty("REPORTING_MAX_POOL_SIZE", "2")
                .withProperty("REPORTING_POOL_NAME", "configuration-tests");

        try (var pool = JdbcDataSources.create(environment, "REPORTING")) {
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE probe (sample_value INT)");
                statement.executeUpdate("INSERT INTO probe VALUES (42)");
            }
            // Независимое соединение подтверждает реальное применение пароля, включая пробелы.
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:configuration-tests", "sa", password);
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT sample_value FROM probe")) {
                rows.next();
                assertEquals(42, rows.getInt(1));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\r\n"})
    void secretFilesRemoveOnlyOneTerminalLineEnding(String ending) throws Exception {
        var environment = new MockEnvironment().withProperty("DB_PASSWORD_FILE",
                secretFile("password", " meaningful spaces " + ending));

        assertEquals(" meaningful spaces ", SecretValues.read(environment, "DB_PASSWORD", null));
    }

    @Test
    void conflictingOrMissingSecretsFailWithoutEchoingTheirValues() throws Exception {
        String secret = "must-not-appear-in-errors";
        var conflict = new MockEnvironment().withProperty("DB_PASSWORD", secret)
                .withProperty("DB_PASSWORD_FILE", secretFile("password", secret));

        var exception = assertThrows(IllegalArgumentException.class,
                () -> SecretValues.read(conflict, "DB_PASSWORD", null));
        assertFalse(exception.getMessage().contains(secret));
        assertThrows(IllegalArgumentException.class,
                () -> SecretValues.read(new MockEnvironment(), "DB_PASSWORD", null));
        assertThrows(IllegalStateException.class,
                () -> SecretValues.read(new MockEnvironment().withProperty("DB_PASSWORD_FILE",
                        directory.resolve("missing").toString()), "DB_PASSWORD", null));
    }

    @Test
    void externalJdbcConnectionRejectsEmptyCredentialsBeforeOpeningThePool() {
        var environment = new MockEnvironment().withProperty("DB_URL", "jdbc:postgresql://localhost:5432/blog")
                .withProperty("DB_USER", "blog").withProperty("DB_PASSWORD", "")
                .withProperty("DB_MAX_POOL_SIZE", "5").withProperty("DB_POOL_NAME", "invalid-credentials");

        assertThrows(IllegalArgumentException.class, () -> JdbcDataSources.create(environment, "DB"));
        environment.withProperty("DB_USER", " ").withProperty("DB_PASSWORD", "configured");
        assertThrows(IllegalArgumentException.class, () -> JdbcDataSources.create(environment, "DB"));
    }

    @Test
    void invalidCorsPolicyFailsAtConfigurationInsteadOfServingRequests() {
        var environment = new MockEnvironment().withProperty("CORS_ORIGINS", "http://localhost")
                .withProperty("CORS_MAX_AGE_SECONDS", "3600");
        assertDoesNotThrow(() -> new WebConfig(environment));
        environment.withProperty("CORS_ORIGINS", "https://*.example.com");
        assertThrows(IllegalArgumentException.class, () -> new WebConfig(environment));
        environment.withProperty("CORS_ORIGINS", "http://localhost").withProperty("CORS_MAX_AGE_SECONDS", "-1");
        assertThrows(IllegalArgumentException.class, () -> new WebConfig(environment));
    }

    private String secretFile(String filename, String content) throws Exception {
        Path file = directory.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toString();
    }
}
