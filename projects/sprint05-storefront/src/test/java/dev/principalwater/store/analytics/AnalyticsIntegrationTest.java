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
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@SpringBootTest(properties = {
        "store.analytics.enabled=true",
        "store.analytics.flush-delay=PT1H",
        "store.analytics.url=${STORE_TEST_ANALYTICS_URL}",
        "store.analytics.writer-password=${STORE_TEST_ANALYTICS_WRITER_PASSWORD}",
        "store.analytics.reader-password=${STORE_TEST_ANALYTICS_READER_PASSWORD}"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnalyticsIntegrationTest {
    @Autowired private CatalogImportService importer;
    @Autowired private ProductRepository products;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService orders;
    @Autowired private AnalyticsQueue queue;
    @Autowired private AnalyticsExporter exporter;
    @Autowired private ClickHouseClient clickhouse;
    @Autowired private ObjectMapper json;
    @Autowired private MockMvc mvc;

    @Test
    void realCheckoutRetriesUnknownOutcomeAndRecoversAfterWarehouseOutage() throws Exception {
        importer.importJson(json.writeValueAsBytes(List.of(new ProductInput(
                "analytics-keyboard", "Клавиатура", "Снимок заказа", ImageAsset.KEYBOARD, 7900L))));
        long productId = products.findAllBySkuIn(List.of("analytics-keyboard")).getFirst().getId();
        var firstCart = carts.identify(null);
        carts.change(firstCart, productId, CartAction.PLUS);
        carts.change(firstCart, productId, CartAction.PLUS);
        long firstOrder = checkout.buy(firstCart);
        var batch = queue.capture();
        assertEquals(List.of(firstOrder), batch.orderIds());

        // В тестовом сервере merge остановлен: без FINAL повторные строки увеличат сумму.
        clickhouse.insert(batch.facts());
        clickhouse.insert(batch.facts());
        var total = total();
        assertEquals(1, total.orders());
        assertEquals(2, total.units());
        assertEquals(15800, total.orderValue());
        assertEquals(1, queue.status().pendingOrders());
        assertEquals(1, exporter.flush());
        assertEquals(0, queue.status().pendingOrders());
        mvc.perform(get("/analytics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-metric=\"orders\">1")))
                .andExpect(content().string(containsString("Клавиатура")));

        docker("stop");
        try {
            var secondCart = carts.identify(null);
            carts.change(secondCart, productId, CartAction.PLUS);
            long secondOrder = checkout.buy(secondCart);
            assertEquals(7900, orders.get(secondOrder, secondCart).totalSum());
            assertTrue(carts.view(secondCart).items().isEmpty());
            assertEquals(StoreError.ANALYTICS_UNAVAILABLE, assertThrows(StoreException.class, exporter::flush).getError());
            assertEquals(1, queue.status().pendingOrders());
            mvc.perform(get("/analytics"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Аналитика временно недоступна")))
                    .andExpect(content().string(not(containsString("data-metric=\"orders\""))));
        } finally {
            docker("start");
        }
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .untilAsserted(() -> assertEquals(1, total().orders()));
        assertEquals(1, exporter.flush());
        assertEquals(0, queue.status().pendingOrders());
        total = total();
        assertEquals(2, total.orders());
        assertEquals(3, total.units());
        assertEquals(23700, total.orderValue());
        assertTrue(queue.status().lastDelivery() != null);
        mvc.perform(get("/analytics").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("--days:30")));
        mvc.perform(get("/analytics").param("days", "365"))
                .andExpect(status().isBadRequest());
    }

    private AnalyticsRow total() {
        return clickhouse.dashboard(queue.sourceId(), LocalDate.now(ZoneOffset.UTC).minusDays(6), LocalDate.now(ZoneOffset.UTC)).stream()
                .filter(row -> row.bucket() == AnalyticsBucket.TOTAL).findFirst().orElseThrow();
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
