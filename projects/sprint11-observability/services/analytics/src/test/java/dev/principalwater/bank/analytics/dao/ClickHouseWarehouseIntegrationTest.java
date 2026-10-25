package dev.principalwater.bank.analytics.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.principalwater.bank.analytics.model.ActivitySnapshot;
import dev.principalwater.bank.contract.BankEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

class ClickHouseWarehouseIntegrationTest {
    private static final String PASSWORD = "fixture-password";
    private static final ObjectMapper JSON =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private static final List<UUID> DEMO =
            List.of(
                    UUID.fromString("de110000-0000-4000-8000-000000000001"),
                    UUID.fromString("de110000-0000-4000-8000-000000000002"),
                    UUID.fromString("de110000-0000-4000-8000-000000000003"));

    @Test
    void upgradedViewsSeparateSyntheticOriginsAndCoreSummaryKeepsOnlyRealOwners() throws Exception {
        Path database = Path.of("../../deployment/database");
        try (GenericContainer<?> clickhouse =
                new GenericContainer<>(
                                "clickhouse/clickhouse-server:26.8-alpine@sha256:2ad322429c7965bde31e3a6389daa2df62db7e1898b1b91d50eb3a7bad716f1f")
                        .withEnv("CLICKHOUSE_USER", "fixture-admin")
                        .withEnv("CLICKHOUSE_PASSWORD", PASSWORD)
                        .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                        .withExposedPorts(8123)
                        .withTmpFs(Map.of("/var/lib/clickhouse-cold", "rw,mode=1777"))
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(
                                        database.resolve("clickhouse-storage.xml")),
                                "/etc/clickhouse-server/config.d/bank-storage.xml")
                        .withCreateContainerCmdModifier(
                                command ->
                                        command.getHostConfig()
                                                .withMemory(768L * 1024 * 1024)
                                                .withNanoCPUs(2_000_000_000L))
                        .waitingFor(
                                Wait.forHttp("/ping")
                                        .withBasicCredentials("fixture-admin", PASSWORD))) {
            clickhouse.start();
            sql(clickhouse, Files.readString(database.resolve("init-clickhouse.sql")));
            sql(
                    clickhouse,
                    "CREATE USER bank_writer IDENTIFIED WITH sha256_password BY 'fixture-password'; GRANT ALL ON bank_analytics.* TO bank_writer");
            for (String view : List.of("financial_daily", "engagement_daily", "notification_daily"))
                sql(
                        clickhouse,
                        "CREATE VIEW bank_analytics." + view + " AS SELECT 1 AS legacy_column");
            String marts;
            try (var resource = getClass().getResourceAsStream("/clickhouse/activity-marts.sql")) {
                marts =
                        new String(
                                resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            sql(clickhouse, marts);
            URI endpoint =
                    URI.create(
                            "http://"
                                    + clickhouse.getHost()
                                    + ":"
                                    + clickhouse.getMappedPort(8123));
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            // Похожий UUID остаётся рабочим: исключение разрешено только для трёх резервных
            // владельцев.
            UUID live = UUID.fromString("de110000-0000-4000-8000-000000000004");
            UUID otherLive = UUID.fromString("ee486035-62dd-41d8-af0c-382f60d1c068");
            try (ClickHouseWarehouse warehouse =
                            new ClickHouseWarehouse(endpoint, "bank_writer", PASSWORD, JSON);
                    ClickHouseActivityMart activity =
                            new ClickHouseActivityMart(endpoint, "bank_writer", PASSWORD, JSON)) {
                for (UUID owner : DEMO) deposit(warehouse, owner, today, 1000);
                var empty = warehouse.month(YearMonth.from(today));
                assertThat(empty.accountsCount()).isZero();
                assertThat(empty.operationsCount()).isZero();
                assertThat(empty.days()).isEmpty();
                assertThat(empty.updatedAt()).isNull();
                deposit(warehouse, live, today, 29);
                deposit(warehouse, otherLive, today, 31);
                var real = warehouse.month(YearMonth.from(today));
                assertThat(real.accountsCount()).isEqualTo(2);
                assertThat(real.operationsCount()).isEqualTo(2);
                assertThat(real.days().getFirst().incomingMinor()).isEqualTo(60);
                List<ActivitySnapshot.Row> snapshots = new ArrayList<>();
                for (UUID owner : DEMO) snapshot(snapshots, owner, today, 2, 2, 1);
                snapshot(snapshots, live, today, 7, 4, 3);
                snapshot(snapshots, otherLive, today, 1, 1, 1);
                activity.insert(snapshots);
                List<JsonNode> money =
                        rows(
                                clickhouse,
                                "SELECT data_origin,incoming_minor,accounts_count FROM bank_analytics.financial_daily ORDER BY data_origin");
                assertThat(money)
                        .containsExactly(
                                JSON.readTree(
                                        "{\"data_origin\":\"demo\",\"incoming_minor\":3000,\"accounts_count\":3}"),
                                JSON.readTree(
                                        "{\"data_origin\":\"live\",\"incoming_minor\":60,\"accounts_count\":2}"));
                assertThat(
                                rows(
                                        clickhouse,
                                        "SELECT data_origin,sum(events_count) AS events FROM bank_analytics.engagement_daily GROUP BY data_origin ORDER BY data_origin"))
                        .containsExactly(
                                JSON.readTree("{\"data_origin\":\"demo\",\"events\":6}"),
                                JSON.readTree("{\"data_origin\":\"live\",\"events\":8}"));
                assertThat(
                                rows(
                                        clickhouse,
                                        "SELECT data_origin,available_count,read_count FROM bank_analytics.notification_daily ORDER BY data_origin"))
                        .containsExactly(
                                JSON.readTree(
                                        "{\"data_origin\":\"demo\",\"available_count\":6,\"read_count\":3}"),
                                JSON.readTree(
                                        "{\"data_origin\":\"live\",\"available_count\":5,\"read_count\":4}"));
                // CREATE OR REPLACE обновляет уже применённые views, не дублируя факты при повторе.
                sql(clickhouse, marts);
                assertThat(
                                rows(
                                        clickhouse,
                                        "SELECT data_origin,incoming_minor,accounts_count FROM bank_analytics.financial_daily ORDER BY data_origin"))
                        .isEqualTo(money);
                deposit(warehouse, live, today.minusDays(29), 17);
                deposit(warehouse, live, today.minusDays(30), 71);
                assertThat(
                                rows(
                                        clickhouse,
                                        "SELECT sum(incoming_minor) AS amount FROM bank_analytics.financial_daily WHERE data_origin='live'"))
                        .containsExactly(JSON.readTree("{\"amount\":77}"));
            }
        }
    }

    private static void deposit(
            ClickHouseWarehouse warehouse, UUID owner, LocalDate date, long amount) {
        warehouse.insert(
                new BankEvent(
                        UUID.randomUUID(),
                        owner,
                        "DEPOSIT",
                        amount,
                        amount,
                        date.atTime(12, 0).toInstant(ZoneOffset.UTC)));
    }

    private static void snapshot(
            List<ActivitySnapshot.Row> rows,
            UUID owner,
            LocalDate date,
            long views,
            long available,
            long read) {
        rows.add(
                new ActivitySnapshot.Row(
                        owner, date, "UI", "SCREEN_OPENED", "ANALYTICS", views, 1));
        rows.add(
                new ActivitySnapshot.Row(
                        owner, date, "BACKEND", "NOTIFICATION_AVAILABLE", "", available, 1));
        rows.add(
                new ActivitySnapshot.Row(owner, date, "BACKEND", "NOTIFICATION_READ", "", read, 1));
    }

    private static String sql(GenericContainer<?> server, String sql) throws Exception {
        var result =
                server.execInContainer(
                        "clickhouse-client",
                        "--user",
                        "fixture-admin",
                        "--password",
                        PASSWORD,
                        "--multiquery",
                        "--query",
                        sql);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout();
    }

    private static List<JsonNode> rows(GenericContainer<?> server, String sql) throws Exception {
        String output =
                sql(
                        server,
                        sql
                                + " LIMIT 32 SETTINGS output_format_json_quote_64bit_integers=0,max_execution_time=2,max_rows_to_read=100000,max_bytes_to_read=16777216,max_memory_usage=67108864 FORMAT JSONEachRow");
        try (var rows = JSON.readerFor(JsonNode.class).<JsonNode>readValues(output)) {
            return rows.readAll();
        }
    }
}
