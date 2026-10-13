package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.ProductInput;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogImportService;
import dev.principalwater.store.service.CheckoutService;
import dev.principalwater.store.service.OrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

@SpringBootTest(properties = {
        "store.analytics.enabled=true",
        "store.analytics.flush-delay=PT1H",
        "store.analytics.url=${STORE_TEST_ANALYTICS_URL}",
        "store.analytics.writer-password=${STORE_TEST_ANALYTICS_WRITER_PASSWORD}",
        "store.analytics.reader-password=${STORE_TEST_ANALYTICS_READER_PASSWORD}"
})
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class AnalyticsIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    @Autowired private CatalogImportService importer;
    @Autowired private ProductRepository products;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService orders;
    @Autowired private AnalyticsQueue queue;
    @Autowired private AnalyticsDeliveryCoordinator delivery;
    @Autowired private ClickHouseClient clickhouse;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @Autowired private DatabaseClient database;
    @Autowired private Environment environment;
    @Autowired private WebTestClient web;

    @BeforeEach
    @AfterEach
    void cleanIsolatedDatabase() {
        String url = environment.getRequiredProperty("spring.r2dbc.url");
        assertTrue(url.contains("/store-tests6") || url.endsWith("/store_tests"),
                "Analytics integration cleanup requires its isolated test database");
        Flux.just("analytics_outbox", "order_items", "purchase_orders", "cart_items", "shopping_carts", "products")
                .concatMap(table -> database.sql("DELETE FROM " + table).fetch().rowsUpdated()).then().block(TIMEOUT);
        web = web.mutate().responseTimeout(TIMEOUT).build();
    }

    @Test
    void realCheckoutRetriesUnknownOutcomeAndRecoversAfterWarehouseOutage() throws Exception {
        // Пустое окно доступно, но среднего без заказов нет: wire-контракт сохраняет явный null.
        web.get().uri("/profile/analytics/data").accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.summary.orders").isEqualTo("0")
                .jsonPath("$.summary.averageOrder").hasJsonPath()
                .jsonPath("$.summary.averageOrder").value(value -> assertNull(value));
        web.get().uri("/profile/analytics").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("data-metric=\"average-order\">—")));
        value(importer.importJson(json.writeValueAsBytes(List.of(new ProductInput(
                "analytics-keyboard", "Клавиатура", "Снимок заказа", ImageAsset.KEYBOARD, 7900L)))));
        long productId = value(products.findAllBySkuIn(List.of("analytics-keyboard")).single()).id();
        var firstCart = value(carts.identify(null));
        value(carts.change(firstCart, productId, CartAction.SET, 2));
        long firstOrder = value(checkout.buy(firstCart));
        var batch = value(queue.capture());
        assertEquals(List.of(firstOrder), batch.orderIds());
        assertNotNull(value(queue.status()).oldestPending());

        // На отдельном сервере merge остановлен: без FINAL повторные строки увеличат сумму.
        StepVerifier.create(clickhouse.insert(batch.facts())).expectComplete().verify(TIMEOUT);
        StepVerifier.create(clickhouse.insert(batch.facts())).expectComplete().verify(TIMEOUT);
        var fact = batch.facts().getFirst();
        var foreign = new AnalyticsFact(UUID.randomUUID(), fact.orderUuid(), fact.orderId(), fact.createdAt(),
                fact.lineNumber(), fact.productId(), fact.sku(), fact.title(), 1_000_000, 99);
        StepVerifier.create(clickhouse.insert(List.of(foreign))).expectComplete().verify(TIMEOUT);
        var total = total();
        assertEquals(1, total.orders());
        assertEquals(2, total.units());
        assertEquals(15800, total.orderValue());
        assertEquals(1, value(queue.status()).pendingOrders());
        StepVerifier.create(delivery.flush()).expectNext(DeliveryResult.delivered(1))
                .expectComplete().verify(TIMEOUT);
        assertEquals(0, value(queue.status()).pendingOrders());
        web.get().uri("/profile/analytics").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("data-metric=\"orders\">1"));
                    assertTrue(html.contains("Клавиатура"));
                    assertFalse(html.contains("Было 0,00"));
                });

        docker("stop");
        try {
            var secondCart = value(carts.identify(null));
            value(carts.change(secondCart, productId, CartAction.PLUS));
            long secondOrder = value(checkout.buy(secondCart));
            assertEquals(7900, value(orders.get(secondOrder, secondCart)).totalSum());
            assertTrue(value(carts.view(secondCart)).items().isEmpty());
            StepVerifier.create(delivery.flush()).expectErrorSatisfies(failure ->
                    assertEquals(StoreError.ANALYTICS_UNAVAILABLE,
                            assertInstanceOf(StoreException.class, failure).getError())).verify(TIMEOUT);
            assertEquals(1, value(queue.status()).pendingOrders());
            web.get().uri("/profile/analytics").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                    .expectBody(String.class).value(html -> {
                        assertTrue(html.contains("data-state=\"UNAVAILABLE\""));
                        assertTrue(html.contains("Аналитика сейчас недоступна"));
                        assertFalse(html.contains("data-metric=\"orders\""));
                    });
            var sync = web.mutateWith(csrf()).post().uri("/profile/analytics/sync").accept(MediaType.TEXT_HTML)
                    .body(BodyInserters.fromFormData("days", "30")).exchange().expectStatus().isSeeOther()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/profile/analytics?days=30")
                    .expectBody().returnResult();
            web.get().uri("/profile/analytics?days=30").accept(MediaType.TEXT_HTML)
                    .cookies(cookies -> sync.getResponseCookies().forEach((name, values) ->
                            values.forEach(cookie -> cookies.add(name, cookie.getValue()))))
                    .exchange().expectStatus().isOk().expectBody(String.class).value(html ->
                            assertTrue(html.contains("Аналитика пока недоступна. Заказы остаются в очереди.")));
        } finally {
            docker("start");
        }
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .untilAsserted(() -> assertEquals(1, total().orders()));
        StepVerifier.create(delivery.flush()).expectNext(DeliveryResult.delivered(1))
                .expectComplete().verify(TIMEOUT);
        var status = value(queue.status());
        assertEquals(0, status.pendingOrders());
        assertNotNull(status.lastDelivery());
        assertNull(status.oldestPending());
        total = total();
        assertEquals(2, total.orders());
        assertEquals(3, total.units());
        assertEquals(23700, total.orderValue());
        web.get().uri("/profile/analytics/data?days=30").accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.summary.orders").isEqualTo("2")
                .jsonPath("$.summary.units").isEqualTo("3")
                .jsonPath("$.summary.orderValue").isEqualTo("23700")
                .jsonPath("$.summary.averageOrder").isEqualTo("11850.00")
                .jsonPath("$.previous.orders").isEqualTo("0")
                .jsonPath("$.previous.averageOrder").hasJsonPath()
                .jsonPath("$.previous.averageOrder").value(value -> assertNull(value))
                .jsonPath("$.period.days").isEqualTo(30)
                .jsonPath("$.daily.length()").isEqualTo(30);
        web.get().uri("/profile/analytics?days=365").accept(MediaType.TEXT_HTML)
                .exchange().expectStatus().isBadRequest();
    }

    private AnalyticsRow total() {
        var end = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return value(queue.sourceId().flatMap(source -> clickhouse.dashboard(source, end.minusDays(6), end)))
                .stream().filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst().orElseThrow();
    }

    private <T> T value(Mono<T> operation) {
        return Objects.requireNonNull(operation.block(TIMEOUT), "Expected a reactive result");
    }

    private void docker(String action) throws Exception {
        String container = System.getenv("STORE_TEST_CLICKHOUSE_CONTAINER");
        if (container == null || !container.startsWith("storefront-analytics-test-")) {
            throw new IllegalStateException("Warehouse lifecycle test requires its own named container");
        }
        var process = new ProcessBuilder("docker", action, container).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
