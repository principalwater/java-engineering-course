package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseClientTest {
    private static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate START = LocalDate.parse("2026-10-06");
    private static final LocalDate END = LocalDate.parse("2026-10-12");
    private static final String TOTAL = """
            {"bucket":3,"day":"1970-01-01","sku":"","title":"","orders":"1","units":"2","order_value":"15800"}
            """;
    private HttpServer server;
    private ExecutorService workers;
    private volatile HttpHandler handler;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void startServer() throws IOException {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop(0);
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void insertsUtf8SnapshotsWithWriterCredentialsAndReadsTypedAggregates() throws Exception {
        var writes = new AtomicInteger();
        handler = exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertTrue(exchange.getRequestURI().getRawQuery().contains("wait_end_of_query=1"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.startsWith("INSERT")) {
                assertEquals("storefront_writer", exchange.getRequestHeaders().getFirst("X-ClickHouse-User"));
                assertEquals("writer-test-secret", exchange.getRequestHeaders().getFirst("X-ClickHouse-Key"));
                assertTrue(exchange.getRequestURI().getRawQuery().contains("wait_for_async_insert=1"));
                var fact = json.readTree(body.substring(body.indexOf('\n') + 1));
                assertEquals("Клавиатура", fact.get("title").asText());
                assertEquals(SOURCE.toString(), fact.get("source_id").asText());
                writes.incrementAndGet();
                respond(exchange, 200, "");
            } else {
                assertEquals("storefront_reader", exchange.getRequestHeaders().getFirst("X-ClickHouse-User"));
                assertEquals("reader-test-secret", exchange.getRequestHeaders().getFirst("X-ClickHouse-Key"));
                assertTrue(exchange.getRequestURI().getRawQuery().contains("param_source=" + SOURCE));
                respond(exchange, 200, TOTAL);
            }
        };
        try (var client = client()) {
            client.insert(List.of(fact()));
            var rows = client.dashboard(SOURCE, START, END);
            assertEquals(1, writes.get());
            assertEquals(AnalyticsBucket.TOTAL, rows.getFirst().bucket());
            assertEquals(15800, rows.getFirst().orderValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"bucket\":3,\"day\":\"1970-01-01\",\"sku\":\"\",\"title\":\"\",\"orders\":1,\"units\":2}",
            "{\"bucket\":3,\"day\":\"1970-01-01\",\"sku\":\"\",\"title\":\"\",\"orders\":1,\"units\":2,\"order_value\":1.5}",
            "{\"bucket\":3,\"day\":\"1970-01-01\",\"sku\":\"\",\"title\":\"\",\"orders\":1,\"units\":-2,\"order_value\":10}",
            "{\"bucket\":0,\"day\":\"1970-01-01\",\"sku\":\"\",\"title\":\"\",\"orders\":1,\"units\":2,\"order_value\":10}",
            "{\"bucket\":3,\"day\":\"1970-01-01\",\"sku\":\"\",\"title\":\"\",\"orders\":1,\"units\":2,\"order_value\":10}\nCode: 241. Late query error"
    })
    void malformedOrPartialHttp200CannotBecomeValidMetrics(String body) {
        handler = exchange -> respond(exchange, 200, body);
        try (var client = client()) {
            var error = assertThrows(StoreException.class, () -> client.dashboard(SOURCE, START, END));
            assertEquals(StoreError.ANALYTICS_UNAVAILABLE, error.getError());
            assertNotNull(error.getCause());
        }
    }

    @Test
    void failedInsertAndRedirectCannotAcknowledgeDeliveryOrForwardCredentials() {
        var redirects = new AtomicInteger();
        server.createContext("/capture", exchange -> {
            redirects.incrementAndGet();
            respond(exchange, 200, "");
        });
        try (var client = client()) {
            for (int status : List.of(500, 200, 302)) {
                handler = exchange -> {
                    if (status == 200) {
                        exchange.getResponseHeaders().set("X-ClickHouse-Exception-Code", "241");
                    }
                    if (status == 302) {
                        exchange.getResponseHeaders().set("Location", endpoint() + "/capture");
                    }
                    respond(exchange, status, "");
                };
                assertEquals(StoreError.ANALYTICS_UNAVAILABLE,
                        assertThrows(StoreException.class, () -> client.insert(List.of(fact()))).getError());
            }
            handler = exchange -> respond(exchange, 200, "Code: 60. Late insertion error");
            assertThrows(StoreException.class, () -> client.insert(List.of(fact())));
            assertEquals(0, redirects.get());
        }
    }

    @Test
    void oversizedChunkedResponseIsRejectedBeforeDecodingAnUnboundedStream() {
        handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                // Без лимита это валидный JSONEachRow: отказ должен произойти из-за размера.
                output.write(TOTAL.repeat(4000).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Клиент намеренно отменяет поток после превышения лимита.
            }
        };
        try (var client = client()) {
            var error = assertThrows(StoreException.class, () -> client.dashboard(SOURCE, START, END));
            assertEquals(StoreError.ANALYTICS_UNAVAILABLE, error.getError());
        }
    }

    @Test
    void operationDeadlineIncludesTheBodyAfterSuccessfulResponseHeaders() {
        var finish = new CountDownLatch(1);
        handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(TOTAL.getBytes(StandardCharsets.UTF_8));
                output.flush();
                finish.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        try (var client = client()) {
            assertTimeout(Duration.ofSeconds(11), () -> assertThrows(StoreException.class,
                    () -> client.dashboard(SOURCE, START, END)));
        } finally {
            finish.countDown();
        }
    }

    @Test
    void interruptedOperationPreservesTheCallingThreadsInterruptFlag() {
        handler = exchange -> respond(exchange, 200, TOTAL);
        try (var client = client()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(StoreException.class, () -> client.dashboard(SOURCE, START, END));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    private ClickHouseClient client() {
        var properties = new AnalyticsProperties();
        properties.setUrl(URI.create(endpoint()));
        properties.setReaderPassword("reader-test-secret");
        properties.setWriterPassword("writer-test-secret");
        return new ClickHouseClient(properties, json);
    }

    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private AnalyticsFact fact() {
        return new AnalyticsFact(SOURCE, UUID.fromString("22222222-2222-2222-2222-222222222222"),
                1, "2026-10-12 10:00:00.000", 0, 1, "keyboard", "Клавиатура", 7900, 2);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
