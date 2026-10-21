package dev.principalwater.bank.bi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupersetAssetsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TITLES =
            List.of(
                    "Движение денег по дням",
                    "Завершённые операции",
                    "Действия в кабинете",
                    "Активность по дням",
                    "Доставка и прочтение",
                    "Уведомления по дням");
    private static final List<String> CHART_UUIDS =
            List.of(
                    "27e07a35-a03e-33ed-81c0-e02f836f0650", "8237061a-3520-3eee-90a8-577d4c757f7a",
                    "20b0ce5b-7c98-3d48-83a9-69e0abe00814", "c62f71c6-5856-374f-b62b-d2a4b143218f",
                    "1ca0f555-120c-399a-96d3-fa527922b551", "faa40887-15be-3844-a10e-c15cbf5aa535");
    @TempDir static Path directory;
    private static HttpsServer server;
    private static final Map<Integer, Integer> QUERIES = new ConcurrentHashMap<>();
    private static final Set<String> VISITED = ConcurrentHashMap.newKeySet();
    private static volatile String scenario = "healthy";

    @BeforeAll
    static void startApi() throws Exception {
        Path keys = directory.resolve("server.p12");
        Process keytool =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "fixture",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "san=dns:localhost",
                                "-validity",
                                "2",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                keys.toString(),
                                "-storepass",
                                "fixture-password")
                        .redirectErrorStream(true)
                        .start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keys)) {
            store.load(input, "fixture-password".toCharArray());
        }
        Files.write(directory.resolve("ca.crt"), store.getCertificate("fixture").getEncoded());
        KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, "fixture-password".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext(
                "/api/v1/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath().substring("/api/v1/".length());
                    VISITED.add(path);
                    Object result;
                    if (path.endsWith("/charts")) {
                        boolean financial = path.contains("bank-financial");
                        List<Integer> indexes =
                                financial ? List.of(0, 1) : List.of(0, 1, 2, 3, 4, 5);
                        if (financial && scenario.equals("missing")) indexes = List.of(0);
                        if (financial && scenario.equals("wrong")) indexes = List.of(0, 2);
                        if (financial && scenario.equals("duplicate")) indexes = List.of(0, 0);
                        List<Map<String, Object>> charts = new ArrayList<>();
                        for (int index : indexes) {
                            String title =
                                    !financial && scenario.equals("activity-wrong") && index == 5
                                            ? "Unmanaged chart"
                                            : TITLES.get(index);
                            int id =
                                    financial && scenario.equals("shared-id")
                                            ? index + 111
                                            : index + 11;
                            charts.add(Map.of("id", id, "slice_name", title));
                        }
                        result = charts;
                    } else if (path.startsWith("dashboard/")) {
                        result =
                                Map.of(
                                        "published",
                                        !(path.contains("bank-financial")
                                                && scenario.equals("unpublished")));
                    } else {
                        int id = Integer.parseInt(path.split("/")[1]);
                        QUERIES.merge(id, 1, Integer::sum);
                        result =
                                scenario.equals("query-error") && id == 16
                                        ? List.of(Map.of("error", "Fixture query rejected"))
                                        : List.of(Map.of("data", List.of()));
                    }
                    byte[] body = JSON.writeValueAsBytes(Map.of("result", result));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
    }

    @AfterAll
    static void stopApi() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void resetApi() {
        scenario = "healthy";
        QUERIES.clear();
        VISITED.clear();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"user@host", "host/path", "host?query", "host#fragment", "host\nother", ""})
    void rejectsDatabaseUriInjection(String host) {
        assertThrows(IllegalArgumentException.class, () -> SupersetAssets.archive(host));
    }

    @Test
    void exportsPublishedDashboardsSharingExistingFinancialChartsAndStableIdentities()
            throws Exception {
        Map<String, JsonNode> files = new HashMap<>();
        try (ZipInputStream zip =
                new ZipInputStream(
                        new ByteArrayInputStream(SupersetAssets.archive("clickhouse")),
                        StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                files.put(entry.getName(), JSON.readTree(zip.readAllBytes()));
            }
        }
        Set<String> charts = new HashSet<>();
        Set<String> datasets = new HashSet<>();
        for (Map.Entry<String, JsonNode> file : files.entrySet()) {
            if (file.getKey().startsWith("bank/charts/"))
                charts.add(file.getValue().path("uuid").asText());
            if (file.getKey().startsWith("bank/datasets/"))
                datasets.add(file.getValue().path("uuid").asText());
        }
        assertThat(charts).containsExactlyInAnyOrderElementsOf(CHART_UUIDS);
        assertThat(datasets)
                .containsExactlyInAnyOrder(
                        "94e32fde-9761-3747-b1bb-3ef3de041e9f",
                        "c3ff0dd2-3088-3cd2-af8c-a50af5b83ffb",
                        "e6c85f54-3c37-3fea-a7bc-3d343e361a34");
        assertDashboard(
                files.get("bank/dashboards/bank.yaml"),
                "bank-activity",
                "9fb25785-f742-3306-a745-99ff77810588",
                CHART_UUIDS,
                3);
        assertDashboard(
                files.get("bank/dashboards/bank-financial.yaml"),
                "bank-financial",
                "dc4d369f-41d6-350f-8cb1-bcf61986d7a5",
                CHART_UUIDS.subList(0, 2),
                1);
    }

    private static void assertDashboard(
            JsonNode dashboard, String slug, String uuid, List<String> charts, int rows) {
        assertThat(dashboard).isNotNull();
        assertThat(dashboard.path("slug").asText()).isEqualTo(slug);
        assertThat(dashboard.path("uuid").asText()).isEqualTo(uuid);
        assertThat(dashboard.path("published").asBoolean()).isTrue();
        assertThat(dashboard.path("dashboard_title").asText())
                .isEqualTo(
                        slug.equals("bank-financial")
                                ? "Банк: финансовые операции"
                                : "Банк: операции и взаимодействие");
        JsonNode position = dashboard.path("position");
        assertThat(position.path("GRID_ID").path("children").size()).isEqualTo(rows);
        List<String> linked = new ArrayList<>();
        position.forEach(
                node -> {
                    node.path("children")
                            .forEach(child -> assertThat(position.has(child.asText())).isTrue());
                    if (node.path("type").asText().equals("CHART"))
                        linked.add(node.path("meta").path("uuid").asText());
                });
        assertThat(linked).containsExactlyInAnyOrderElementsOf(charts);
    }

    @Test
    void verifiesBothDashboardsAndQueriesSharedChartsOnlyOnce() throws Exception {
        try (SupersetApi api = api()) {
            assertDoesNotThrow(api::verifyCharts);
        }
        assertThat(VISITED)
                .contains(
                        "dashboard/bank-activity",
                        "dashboard/bank-financial",
                        "dashboard/bank-activity/charts",
                        "dashboard/bank-financial/charts");
        assertThat(QUERIES).containsOnlyKeys(11, 12, 13, 14, 15, 16);
        assertThat(QUERIES.values()).allMatch(count -> count == 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unpublished",
                "missing",
                "wrong",
                "duplicate",
                "shared-id",
                "activity-wrong",
                "query-error"
            })
    void rejectsUnpublishedOrIncorrectDashboardAssociationsAndFailedQueries(String failure)
            throws Exception {
        scenario = failure;
        try (SupersetApi api = api()) {
            assertThrows(IOException.class, api::verifyCharts);
        }
    }

    private static SupersetApi api() throws Exception {
        return new SupersetApi(
                URI.create("https://localhost:" + server.getAddress().getPort()),
                directory.resolve("ca.crt"));
    }
}
