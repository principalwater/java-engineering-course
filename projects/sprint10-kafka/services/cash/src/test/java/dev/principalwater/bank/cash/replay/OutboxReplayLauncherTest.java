package dev.principalwater.bank.cash.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.messaging.replay.OutboxReplayApplication;
import dev.principalwater.bank.testing.PostgresTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = OutboxReplayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=cash",
            "spring.config.import=classpath:bank-common.properties",
            "spring.flyway.locations=classpath:db/kafka,classpath:db/workflow,classpath:db/delivery,classpath:db/cash"
        })
class OutboxReplayLauncherTest extends PostgresTestBase {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    private Path directory;

    @Override
    protected void resetDatabase() {
        jdbc.execute("TRUNCATE bank_kafka_outbox,bank_intent,bank_outbox RESTART IDENTITY");
    }

    @Test
    void extractedServiceImageClasspathRunsNonWebBoundedReplayAndPreservesKeysPayloadAndLease()
            throws Exception {
        UUID published = seed(BankTopics.CASH_NOTIFICATIONS, "PUBLISHED");
        UUID pending = seed(BankTopics.CASH_NOTIFICATIONS, "PENDING");
        UUID foreign = seed(BankTopics.TRANSFER_NOTIFICATIONS, "PUBLISHED");
        UUID lease = UUID.randomUUID();
        jdbc.update(
                "UPDATE bank_kafka_outbox SET lease_token=?,lease_until=now()+interval '1 hour' WHERE id=?",
                lease,
                pending);
        Path image = extractServiceImage();
        Path secrets = privateDatabaseConfiguration();
        assertThat(runReplay(image, secrets, "--bank.outbox.replay.limit=1"))
                .as("Replay failed; inspect %s", image.resolve("replay.log"))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM bank_kafka_outbox WHERE id=?",
                                String.class,
                                published))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM bank_kafka_outbox WHERE id=?",
                                String.class,
                                foreign))
                .isEqualTo("PUBLISHED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_token FROM bank_kafka_outbox WHERE id=?",
                                UUID.class,
                                pending))
                .isEqualTo(lease);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM bank_kafka_outbox WHERE message_key='stable-owner' AND message_id LIKE 'stable-%' AND payload='{\"type\":\"immutable\"}'::jsonb",
                                Integer.class))
                .isEqualTo(3);
        assertThat(runReplay(image, secrets, "--bank.outbox.replay.limit=1001")).isNotZero();
        assertThat(Files.readString(image.resolve("replay.log")))
                .doesNotContain("Tomcat started", "Netty started");
    }

    private UUID seed(String topic, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bank_kafka_outbox(id,topic,message_key,message_id,payload,status) VALUES (?,?,'stable-owner',?,'{\"type\":\"immutable\"}'::jsonb,?)",
                id,
                topic,
                "stable-" + id,
                status);
        return id;
    }

    private Path extractServiceImage() throws Exception {
        Files.copy(
                Path.of(System.getProperty("bank.replay.service-jar")),
                directory.resolve("application.jar"));
        Process extraction =
                new ProcessBuilder(
                                javaExecutable(),
                                "-Djarmode=tools",
                                "-jar",
                                "application.jar",
                                "extract",
                                "--layers",
                                "--destination",
                                "extracted")
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("extract.log").toFile())
                        .start();
        assertThat(extraction.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(extraction.exitValue()).isZero();
        Path app = Files.createDirectory(directory.resolve("app"));
        for (String layer :
                List.of(
                        "dependencies",
                        "spring-boot-loader",
                        "snapshot-dependencies",
                        "application")) {
            Path root = directory.resolve("extracted").resolve(layer);
            if (!Files.exists(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path source : paths.toList()) {
                    Path target = app.resolve(root.relativize(source));
                    if (Files.isDirectory(source)) Files.createDirectories(target);
                    else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return app;
    }

    private Path privateDatabaseConfiguration() throws Exception {
        Path secrets =
                Files.createDirectory(
                        directory.resolve("private"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        HikariDataSource database = (HikariDataSource) jdbc.getDataSource();
        writeSecret(secrets, "spring.datasource.url", database.getJdbcUrl());
        writeSecret(secrets, "spring.datasource.username", database.getUsername());
        writeSecret(secrets, "spring.datasource.password", database.getPassword());
        return secrets;
    }

    private void writeSecret(Path directory, String name, String value) throws Exception {
        Path file =
                Files.createFile(
                        directory.resolve(name),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, value);
    }

    private int runReplay(Path image, Path secrets, String option) throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                javaExecutable(),
                                "-cp",
                                "application.jar",
                                OutboxReplayApplication.class.getName(),
                                "--BANK_CONFIG_IMPORT=optional:classpath:bank-common.properties",
                                "--spring.config.import=classpath:bank-common.properties,optional:configtree:"
                                        + secrets
                                        + "/",
                                option));
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(image.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(image.resolve("replay.log").toFile());
        builder.environment().put("BANK_CONFIG_IMPORT", "zookeeper:");
        Process replay = builder.start();
        try {
            assertThat(replay.waitFor(30, TimeUnit.SECONDS)).isTrue();
            return replay.exitValue();
        } finally {
            replay.destroyForcibly();
        }
    }

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
