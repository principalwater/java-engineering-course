package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.TestBase;
import dev.principalwater.store.model.StoreError;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
class ClickHouseClusterTest extends TestBase {
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient inspection = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void replicatedShardsDeduplicateAndRejectIncompleteDeliveryWhileAReplicaIsOffline() throws Exception {
        var endpoints = Arrays.stream(required("STORE_TEST_CLUSTER_URLS").split(",")).map(URI::create).toList();
        var containers = required("STORE_TEST_CLUSTER_CONTAINERS").split(",");
        assertEquals(4, endpoints.size());
        assertEquals(4, containers.length);
        assertTrue(Arrays.stream(containers).allMatch(name -> name.startsWith("storefront-cluster-test-")));
        var properties = new AnalyticsProperties();
        properties.setUrls(endpoints);
        properties.setDistributed(true);
        properties.setInsertQuorum(2);
        properties.setWriterPassword(required("STORE_TEST_ANALYTICS_WRITER_PASSWORD"));
        properties.setReaderPassword(required("STORE_TEST_ANALYTICS_READER_PASSWORD"));
        UUID source = UUID.randomUUID();
        var day = LocalDate.now(java.time.ZoneOffset.UTC);
        List<AnalyticsFact> facts = java.util.stream.LongStream.rangeClosed(1, 16).mapToObj(index ->
                new AnalyticsFact(source, new UUID(0, index), UUID.randomUUID(), index,
                        day + " 10:00:00.000", 0, index, "sku-" + index, "Item " + index, 100, 2)).toList();
        var grownBatch = new java.util.ArrayList<>(facts);
        grownBatch.addAll(facts.stream().map(fact -> new AnalyticsFact(source, fact.userId(), UUID.randomUUID(),
                fact.orderId() + 50, fact.createdAt(), fact.lineNumber(), fact.productId(), fact.sku(), fact.title(),
                fact.price(), fact.quantity())).toList());
        for (URI endpoint : endpoints) {
            admin(endpoint, "SYSTEM STOP MERGES storefront.purchase_facts_local");
        }
        try (var client = new ClickHouseClient(properties, json, WebClient.builder())) {
            StepVerifier.create(client.insert(facts)).expectComplete().verify(TIMEOUT);
            // Расширенный replay имеет другой block hash: ReplicatedMergeTree не скрывает дубли за нас.
            StepVerifier.create(client.insert(grownBatch)).expectComplete().verify(TIMEOUT);
            for (URI endpoint : endpoints) {
                admin(endpoint, "SYSTEM SYNC REPLICA storefront.purchase_facts_local");
            }
            long firstShard = count(endpoints.get(0), source);
            long secondShard = count(endpoints.get(2), source);
            assertTrue(firstShard > 0 && secondShard > 0, "Deterministic user keys must reach both shards");
            assertEquals(32, firstShard + secondShard);
            assertEquals(firstShard, count(endpoints.get(1), source));
            assertEquals(secondShard, count(endpoints.get(3), source));
            StepVerifier.create(client.dashboard(source, facts.getFirst().userId(), day, day)).assertNext(rows -> {
                var total = rows.stream().filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst().orElseThrow();
                assertEquals(2, total.orders());
                assertEquals(4, total.units());
                assertEquals(400, total.orderValue());
            }).expectComplete().verify(TIMEOUT);
            // Реплика и coordinator совпадают: чтение переключается, INSERT с quorum=2 не получает ложного ACK.
            var nextFacts = facts.stream().map(fact -> new AnalyticsFact(source, fact.userId(), UUID.randomUUID(),
                    fact.orderId() + 100, fact.createdAt(), fact.lineNumber(), fact.productId(), fact.sku(), fact.title(),
                    fact.price(), fact.quantity())).toList();
            docker("stop", containers[0]);
            try {
                StepVerifier.create(client.dashboard(source, facts.getFirst().userId(), day, day))
                        .assertNext(rows -> assertEquals(400, rows.stream()
                                .filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst().orElseThrow().orderValue()))
                        .expectComplete().verify(TIMEOUT);
                StepVerifier.create(client.insert(nextFacts)).expectErrorSatisfies(error ->
                        assertStoreError(StoreError.ANALYTICS_UNAVAILABLE, error)).verify(TIMEOUT);
            } finally {
                docker("start", containers[0]);
                awaitReady(endpoints.get(0));
            }
            StepVerifier.create(client.insert(nextFacts)).expectComplete().verify(TIMEOUT);
            StepVerifier.create(client.dashboard(source, facts.getFirst().userId(), day, day))
                    .assertNext(rows -> assertEquals(3, rows.stream()
                            .filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst().orElseThrow().orders()))
                    .expectComplete().verify(TIMEOUT);
        }
    }

    private long count(URI endpoint, UUID source) throws Exception {
        return Long.parseLong(admin(endpoint, "SELECT count() FROM storefront.purchase_facts_local FINAL WHERE source_id = '"
                + source + "'" ).strip());
    }

    private String admin(URI endpoint, String query) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("X-ClickHouse-User", "storefront_admin")
                .header("X-ClickHouse-Key", required("STORE_TEST_ANALYTICS_ADMIN_PASSWORD"))
                .POST(HttpRequest.BodyPublishers.ofString(query)).build();
        var response = inspection.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private void awaitReady(URI endpoint) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                admin(endpoint, "SYSTEM SYNC REPLICA storefront.purchase_facts_local");
                return;
            } catch (IOException | AssertionError failure) {
                if (System.nanoTime() >= deadline) {
                    throw failure;
                }
                Thread.sleep(200);
            }
        }
    }

    private void docker(String action, String container) throws Exception {
        Process process = new ProcessBuilder("docker", action, container).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Container command timed out");
        }
        assertEquals(0, process.exitValue(), output);
    }

    private String required(String key) {
        return java.util.Objects.requireNonNull(System.getenv(key), key + " is required");
    }
}
