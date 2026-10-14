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
import java.util.concurrent.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.LoopResources;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class ClickHouseClientTest extends dev.principalwater.store.TestBase {
    private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate START = LocalDate.parse("2026-10-06");
    private static final LocalDate END = LocalDate.parse("2026-10-12");
    private static final Duration TIMEOUT = Duration.ofSeconds(11);
    private static final String TOTAL = """
            {"bucket":3,"day":"1970-01-01","sku":"","title":"","orders":"1","units":"2","order_value":"9007199254740993"}
            """;
    private HttpServer server;
    private ExecutorService workers;
    private volatile HttpHandler handler;
    private final Scheduler codec = Schedulers.newBoundedElastic(1, 32, "test-codec");
    private final LoopResources loops = LoopResources.create("test-http", 2, true);
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
        codec.dispose();
        loops.disposeLater().block(TIMEOUT);
        server.stop(0);
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void insertsUtf8SnapshotsWithWriterCredentialsAndReadsExactTypedAggregates() {
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
                assertEquals(USER.toString(), fact.get("user_id").asText());
                writes.incrementAndGet();
                respond(exchange, 200, "");
            } else {
                assertEquals("storefront_reader", exchange.getRequestHeaders().getFirst("X-ClickHouse-User"));
                assertEquals("reader-test-secret", exchange.getRequestHeaders().getFirst("X-ClickHouse-Key"));
                assertTrue(exchange.getRequestURI().getRawQuery().contains("param_source=" + SOURCE));
                assertTrue(exchange.getRequestURI().getRawQuery().contains("param_user=" + USER));
                respond(exchange, 200, TOTAL);
            }
        };
        try (var client = client()) {
            StepVerifier.create(client.insert(List.of(fact()))).expectComplete().verify(TIMEOUT);
            StepVerifier.create(client.dashboard(SOURCE, USER, START, END)).assertNext(rows -> {
                assertEquals(1, writes.get());
                assertEquals(1, rows.size());
                assertEquals(AnalyticsBucket.TOTAL, rows.getFirst().bucket());
                assertEquals(9_007_199_254_740_993L, rows.getFirst().orderValue());
                // Число больше 2^53 проходит Java без округления и возвращается в JS строкой.
                assertEquals("9007199254740993", json.valueToTree(rows.getFirst()).get("order_value").asText());
                assertTrue(json.valueToTree(rows.getFirst()).get("order_value").isTextual());
            }).expectComplete().verify(TIMEOUT);
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
            unavailable(client.dashboard(SOURCE, USER, START, END));
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
                unavailable(client.insert(List.of(fact())));
            }
            handler = exchange -> respond(exchange, 200, "Code: 60. Late insertion error");
            unavailable(client.insert(List.of(fact())));
            assertEquals(0, redirects.get());
        }
    }

    @Test
    void oversizedChunkedResponseIsRejectedBeforeDecodingAnUnboundedStream() {
        handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                // Без лимита это валидный JSONEachRow: причина отказа — размер тела.
                output.write(TOTAL.repeat(4000).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Клиент намеренно отменяет поток после превышения лимита.
            }
        };
        try (var client = client()) {
            unavailable(client.dashboard(SOURCE, USER, START, END));
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
            assertTimeout(TIMEOUT, () -> unavailable(client.dashboard(SOURCE, USER, START, END)));
        } finally {
            finish.countDown();
        }
    }

    @Test
    void cancellingIncompleteBodiesDoesNotExhaustTheHttpConnectionPool() {
        var finish = new CountDownLatch(1);
        var headers = new Semaphore(0);
        handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(TOTAL.getBytes(StandardCharsets.UTF_8));
                output.flush();
                headers.release();
                finish.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        try (var client = client()) {
            // Четыре отмены заполнят весь пул, если незавершённые response не освобождаются.
            for (int index = 0; index < 4; index++) {
                StepVerifier.create(client.dashboard(SOURCE, USER, START, END)).then(() -> {
                    try {
                        assertTrue(headers.tryAcquire(3, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Request headers were not observed", exception);
                    }
                }).thenCancel().verify(TIMEOUT);
            }
            handler = exchange -> respond(exchange, 200, TOTAL);
            StepVerifier.create(client.dashboard(SOURCE, USER, START, END)).assertNext(rows -> assertEquals(1, rows.size()))
                    .expectComplete().verify(TIMEOUT);
        } finally {
            finish.countDown();
        }
    }

    @Test
    void unavailableCoordinatorFallsBackWithoutDuplicatingReplicaWrites() {
        var writes = new AtomicInteger();
        handler = exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            assertTrue(query.contains("distributed_foreground_insert=1"));
            assertTrue(query.contains("async_insert=0"));
            assertTrue(query.contains("insert_quorum=2"));
            writes.incrementAndGet();
            respond(exchange, 200, "");
        };
        var properties = properties();
        properties.setUrls(List.of(URI.create("http://127.0.0.1:1"), URI.create(endpoint())));
        properties.setDistributed(true);
        properties.setInsertQuorum(2);
        try (var client = new ClickHouseClient(properties, json, WebClient.builder(), codec, loops)) {
            StepVerifier.create(client.insert(List.of(fact()))).expectComplete().verify(TIMEOUT);
            assertEquals(1, writes.get());
        }
    }

    private void unavailable(Mono<?> operation) {
        StepVerifier.create(operation).expectErrorSatisfies(failure -> {
            var error = assertInstanceOf(StoreException.class, failure);
            assertEquals(StoreError.ANALYTICS_UNAVAILABLE, error.getError());
            assertNotNull(error.getCause());
        }).verify(TIMEOUT);
    }

    private AnalyticsProperties properties() {
        var properties = new AnalyticsProperties();
        properties.setUrl(URI.create(endpoint()));
        properties.setReaderPassword("reader-test-secret");
        properties.setWriterPassword("writer-test-secret");
        return properties;
    }

    private ClickHouseClient client() {
        return new ClickHouseClient(properties(), json, WebClient.builder(), codec, loops);
    }

    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private AnalyticsFact fact() {
        return new AnalyticsFact(SOURCE, USER, UUID.fromString("22222222-2222-2222-2222-222222222222"),
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
