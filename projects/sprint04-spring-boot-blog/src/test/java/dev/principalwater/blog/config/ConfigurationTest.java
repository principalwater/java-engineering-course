package dev.principalwater.blog.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void configTreeBindsFileCredentialsWithoutLosingPasswordSpaces() throws Exception {
        String password = " meaningful spaces ";
        Files.writeString(directory.resolve("spring.datasource.password"), password + "\n");

        databaseContext().run(context -> {
            assertThat(context).hasNotFailed();
            try (var connection = context.getBean(DataSource.class).getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE probe (sample_value INT)");
                statement.executeUpdate("INSERT INTO probe VALUES (42)");
            }
            // Независимое соединение проверяет пароль, реально применённый к H2, а не значение бина.
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:secret-tests", "sa", password);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT sample_value FROM probe")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(42);
            }
        });
    }

    @Test
    void postgresqlProfileRejectsMissingPasswordInsteadOfUsingTheLocalDefault() {
        databaseContext().run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseMessage("PostgreSQL username and password are required"));
    }

    @Test
    void unsafeCorsConfigurationFailsBeforeServingRequests() {
        var context = new ApplicationContextRunner().withUserConfiguration(WebConfig.class)
                .withPropertyValues("blog.cors.origins=http://localhost", "blog.cors.max-age=1h");
        context.run(valid -> assertThat(valid).hasNotFailed());
        context.withPropertyValues("blog.cors.origins=https://*.example.com")
                .run(invalid -> assertThat(invalid).hasFailed());
        context.withPropertyValues("blog.cors.max-age=-1s")
                .run(invalid -> assertThat(invalid).hasFailed());
    }

    private ApplicationContextRunner databaseContext() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(PostgresqlConfig.class)
                .withPropertyValues("spring.profiles.active=postgresql",
                        "BLOG_SECRETS_LOCATION=" + directory + "/",
                        "DB_URL=jdbc:h2:mem:secret-tests", "DB_USER=sa");
    }
}
