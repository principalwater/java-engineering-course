package dev.principalwater.bank.analytics.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.analytics.model.ActivitySnapshot;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BusinessFailureEvent;
import dev.principalwater.bank.contract.BusinessFailureType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClickHouseWarehouseTest {
    private final ObjectMapper mapper =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private HttpServer server;
    private ClickHouseWarehouse warehouse;
    private java.util.concurrent.ExecutorService workers;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newFixedThreadPool(2);
        server.setExecutor(workers);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        handler.get().handle(exchange);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        warehouse =
                new ClickHouseWarehouse(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "writer",
                        "fixture-password",
                        mapper);
    }

    @AfterEach
    void stop() {
        warehouse.close();
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void deliveryReplaysTheSameNativeFactAndWaitsForDurableAsyncAcknowledgement() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        handler.set(
                exchange -> {
                    assertEquals(
                            "writer", exchange.getRequestHeaders().getFirst("X-ClickHouse-User"));
                    assertTrue(
                            exchange.getRequestURI()
                                    .getRawQuery()
                                    .contains("wait_for_async_insert=1"));
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String previous = first.getAndSet(body);
                    if (previous != null) {
                        assertEquals(previous, body);
                    }
                    reply(exchange, 200, "");
                });
        BankEvent event =
                new BankEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "WITHDRAW",
                        -29,
                        100,
                        Instant.parse("2026-10-17T01:02:03.456789Z"));
        warehouse.insert(event);
        warehouse.insert(event);
        JsonNode fact = mapper.readTree(first.get().substring(first.get().indexOf('\n') + 1));
        assertEquals(event.eventId().toString(), fact.path("event_id").asText());
        assertEquals(-29, fact.path("amount_minor").asLong());
        assertEquals("2026-10-17 01:02:03.456", fact.path("occurred_at").asText());
    }

    @Test
    void rejectionIsEnrichedAndReplayedIntoItsOwnTableWithoutBecomingAMoneyMovement()
            throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        handler.set(
                exchange -> {
                    String actual =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String previous = body.getAndSet(actual);
                    if (previous != null) assertEquals(previous, actual);
                    assertTrue(
                            exchange.getRequestURI()
                                    .getRawQuery()
                                    .contains("wait_for_async_insert=1"));
                    reply(exchange, 200, "");
                });
        BusinessFailureEvent fact =
                new BusinessFailureEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        BusinessFailureType.WITHDRAWAL,
                        "INSUFFICIENT_FUNDS",
                        100,
                        Instant.parse("2026-10-17T01:02:03Z"));
        warehouse.insertFailure(fact);
        warehouse.insertFailure(fact);
        assertTrue(
                body.get()
                        .startsWith(
                                "INSERT INTO bank_analytics.business_failures FORMAT JSONEachRow\n"));
        JsonNode row = mapper.readTree(body.get().substring(body.get().indexOf('\n') + 1));
        assertEquals("RUB", row.path("currency").asText());
        assertEquals("cash", row.path("source_service").asText());
        assertEquals(100, row.path("requested_amount_minor").asLong());
        assertTrue(row.path("counterparty_id").isNull());
        assertTrue(row.path("amount_minor").isMissingNode());
    }

    @Test
    void officialClientBatchKeepsSnapshotRevisionAndWaitsForDurableAsyncResponse()
            throws Exception {
        AtomicReference<String> payload = new AtomicReference<>();
        handler.set(
                exchange -> {
                    String query =
                            java.net.URLDecoder.decode(
                                    exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertTrue(query.contains("wait_for_async_insert=1"));
                    payload.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    exchange.getResponseHeaders()
                            .add(
                                    "X-ClickHouse-Summary",
                                    "{\"written_rows\":\"1\",\"written_bytes\":\"100\"}");
                    reply(exchange, 200, "");
                });
        UUID owner = UUID.randomUUID();
        try (ClickHouseActivityMart mart =
                new ClickHouseActivityMart(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "writer",
                        "fixture-password",
                        mapper)) {
            mart.insert(
                    List.of(
                            new ActivitySnapshot.Row(
                                    owner,
                                    LocalDate.of(2026, 10, 19),
                                    "UI",
                                    "SCREEN_OPENED",
                                    "ANALYTICS",
                                    1,
                                    9)));
        }
        JsonNode row = mapper.readTree(payload.get());
        assertEquals(owner.toString(), row.path("owner_id").asText());
        assertEquals("2026-10-19", row.path("event_date").asText());
        assertEquals("SCREEN_OPENED", row.path("event_type").asText());
        assertEquals(1, row.path("events_count").asLong());
        assertEquals(9, row.path("revision").asLong());
    }

    @Test
    void readsActualMonthlyGroupingAndRejectsBadOrOversizedResponsesRatherThanReturningZero() {
        handler.set(exchange -> reply(exchange, 200, summary()));
        AnalyticsView view = warehouse.month(YearMonth.of(2026, 10));
        assertTrue(view.available());
        assertEquals(1L, view.operationsCount());
        assertEquals(2L, view.accountsCount());
        assertEquals(29, view.days().getFirst().incomingMinor());
        assertEquals(Instant.parse("2026-10-17T01:02:03Z"), view.updatedAt());
        for (Handler failure :
                new Handler[] {
                    exchange -> reply(exchange, 503, "unavailable"),
                    exchange -> {
                        exchange.getResponseHeaders().add("X-ClickHouse-Exception-Code", "48");
                        reply(exchange, 200, "");
                    },
                    exchange -> reply(exchange, 200, "x".repeat(70_000)),
                    exchange -> reply(exchange, 200, "{}")
                }) {
            handler.set(failure);
            assertThrows(AnalyticsUnavailable.class, () -> warehouse.month(YearMonth.of(2026, 10)));
        }
    }

    @Test
    void fullBodyDeadlineCancelsAnIncompleteResponseAndReleasesAdmissionForTheNextRead() {
        CountDownLatch release = new CountDownLatch(1);
        handler.set(
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        release.await(6, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5),
                    () ->
                            assertThrows(
                                    AnalyticsUnavailable.class,
                                    () -> warehouse.month(YearMonth.of(2026, 10))));
        } finally {
            release.countDown();
        }
        handler.set(exchange -> reply(exchange, 200, summary()));
        assertTrue(warehouse.month(YearMonth.of(2026, 10)).available());
    }

    private String summary() {
        return """
                {"date":"2026-10-17","summary":0,"incomingMinor":29,"outgoingMinor":17,"operationsCount":1,
                "accountsCount":2,"viewCount":1,"eventCount":3,"updatedAtMillis":1792198923000}
                {"date":"1970-01-01","summary":1,"incomingMinor":29,"outgoingMinor":17,"operationsCount":1,
                "accountsCount":2,"viewCount":1,"eventCount":3,"updatedAtMillis":1792198923000}
                """;
    }

    private void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
