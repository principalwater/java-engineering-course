package dev.principalwater.bank.analytics.dao;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.BankEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
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
