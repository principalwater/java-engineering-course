package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.util.VisitorIdentity;
import dev.principalwater.store.DatabaseTestBase;
import dev.principalwater.store.config.CartProperties;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogImportService;
import dev.principalwater.store.service.CheckoutService;
import dev.principalwater.store.service.OrderService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.test.StepVerifier;

import static dev.principalwater.store.ProductFixtures.productInput;
import static org.junit.jupiter.api.Assertions.*;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

@SpringBootTest(properties = {
        "store.analytics.enabled=true",
        "store.analytics.flush-delay=PT1H",
        "store.analytics.url=${STORE_TEST_ANALYTICS_URL}",
        "store.analytics.writer-password=${STORE_TEST_ANALYTICS_WRITER_PASSWORD}",
        "store.analytics.reader-password=${STORE_TEST_ANALYTICS_READER_PASSWORD}"
})
@AutoConfigureWebTestClient
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class AnalyticsIntegrationTest extends DatabaseTestBase {
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
    @Autowired private WebTestClient web;
    @Autowired private CartProperties cartProperties;

    @BeforeEach
    void boundWebRequests() {
        web = web.mutate().responseTimeout(TIMEOUT).build();
    }

    @Test
    void realCheckoutRetriesUnknownOutcomeAndRecoversAfterWarehouseOutage() throws Exception {
        UUID firstCart = await(carts.identify(null));
        web = web.mutate().defaultCookie(cartProperties.cookieName(), firstCart.toString()).build();
        // Пустое окно доступно, но среднего без заказов нет: wire-контракт сохраняет явный null.
        web.get().uri("/profile/analytics/data").accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.summary.orders").isEqualTo("0")
                .jsonPath("$.summary.averageOrder").hasJsonPath()
                .jsonPath("$.summary.averageOrder").value(value -> assertNull(value));
        web.get().uri("/profile/analytics").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("data-metric=\"average-order\">—")));
        await(importer.importJson(json.writeValueAsBytes(List.of(productInput(
                "analytics-keyboard", "Клавиатура", "Снимок заказа", 7900)))));
        long productId = await(products.findAllBySkuIn(List.of("analytics-keyboard")).single()).id();
        await(carts.change(firstCart, productId, CartAction.SET, 2));
        long firstOrder = await(checkout.buy(firstCart));
        var batch = await(queue.capture());
        assertEquals(List.of(firstOrder), batch.orderIds());
        assertNotNull(await(queue.status(firstCart)).oldestPending());

        // На отдельном сервере merge остановлен: без FINAL повторные строки увеличат сумму.
        StepVerifier.create(clickhouse.insert(batch.facts())).expectComplete().verify(TIMEOUT);
        StepVerifier.create(clickhouse.insert(batch.facts())).expectComplete().verify(TIMEOUT);
        var fact = batch.facts().getFirst();
        assertEquals(VisitorIdentity.analyticsId(firstCart), fact.userId());
        var foreign = new AnalyticsFact(UUID.randomUUID(), UUID.randomUUID(), fact.orderUuid(), fact.orderId(),
                fact.createdAt(), fact.lineNumber(), fact.productId(), fact.sku(), fact.title(), 1_000_000, 99);
        var otherUser = new AnalyticsFact(fact.sourceId(), UUID.randomUUID(), UUID.randomUUID(), fact.orderId(),
                fact.createdAt(), fact.lineNumber(), fact.productId(), fact.sku(), fact.title(), 1_000_000, 99);
        StepVerifier.create(clickhouse.insert(List.of(foreign, otherUser))).expectComplete().verify(TIMEOUT);
        assertEquals(0, total(UUID.randomUUID()).orders());
        var total = total(firstCart);
        assertEquals(1, total.orders());
        assertEquals(2, total.units());
        assertEquals(15800, total.orderValue());
        assertEquals(1, await(queue.status(firstCart)).pendingOrders());
        StepVerifier.create(delivery.flush()).expectNext(DeliveryResult.delivered(1))
                .expectComplete().verify(TIMEOUT);
        assertEquals(0, await(queue.status(firstCart)).pendingOrders());
        web.get().uri("/profile/analytics").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("data-metric=\"orders\">1"));
                    assertTrue(html.contains("Клавиатура"));
                    assertFalse(html.contains("Было 0,00"));
                });

        docker("stop");
        try {
            await(carts.change(firstCart, productId, CartAction.PLUS));
            long secondOrder = await(checkout.buy(firstCart));
            assertEquals(7900, await(orders.get(secondOrder, firstCart)).totalSum());
            assertTrue(await(carts.view(firstCart)).items().isEmpty());
            expectError(delivery.flush(), StoreError.ANALYTICS_UNAVAILABLE);
            assertEquals(1, await(queue.status(firstCart)).pendingOrders());
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
        Awaitility.await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .untilAsserted(() -> assertEquals(1, total(firstCart).orders()));
        StepVerifier.create(delivery.flush()).expectNext(DeliveryResult.delivered(1))
                .expectComplete().verify(TIMEOUT);
        var status = await(queue.status(firstCart));
        assertEquals(0, status.pendingOrders());
        assertNotNull(status.lastDelivery());
        assertNull(status.oldestPending());
        total = total(firstCart);
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

    private AnalyticsRow total(UUID userId) {
        var end = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return await(queue.sourceId().flatMap(source -> clickhouse.dashboard(source, VisitorIdentity.analyticsId(userId), end.minusDays(6), end)))
                .stream().filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst().orElseThrow();
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
