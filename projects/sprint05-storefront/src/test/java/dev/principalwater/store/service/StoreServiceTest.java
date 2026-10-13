package dev.principalwater.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.ProductInput;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoreServiceTest {
    @Autowired private CatalogImportService importer;
    @Autowired private CatalogService catalog;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OutboxRepository outbox;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void catalogSupportsLiteralDescriptionSearchStableSortingAndPagination() throws Exception {
        importer.importJson(json.writeValueAsBytes(List.of(
                input("alpha", "Alpha", "First", 20),
                input("beta", "Beta", "Second", 10),
                input("zeta", "Zeta", "Literal 100%_! fragment", 30))));
        var empty = new CartView(List.of());

        var price = catalog.list(new CatalogQuery("", SortMode.PRICE, 1, 2), empty);
        assertEquals(List.of("Beta", "Alpha"), price.items().stream().map(item -> item.title()).toList());
        assertTrue(price.paging().hasNext());
        assertEquals(3, price.totalItems());
        var alpha = catalog.list(new CatalogQuery("", SortMode.ALPHA, 1, 2), empty);
        assertEquals(List.of("Alpha", "Beta"), alpha.items().stream().map(item -> item.title()).toList());
        var literal = catalog.list(new CatalogQuery("%_!", SortMode.NO, 1, 5), empty);
        assertEquals(List.of("Zeta"), literal.items().stream().map(item -> item.title()).toList());
        assertTrue(catalog.list(new CatalogQuery("", SortMode.NO, 999, 2), empty).items().isEmpty());
    }

    @Test
    void skuUpsertKeepsTheProductIdentityAndExistingCartQuantity() throws Exception {
        var product = input("upsert", "Before", "Description", 100);
        importer.importJson(json.writeValueAsBytes(List.of(product)));
        long id = id("upsert");
        UUID cartId = carts.identify(null);
        carts.change(cartId, id, CartAction.PLUS);
        carts.change(cartId, id, CartAction.PLUS);

        var result = importer.importJson(json.writeValueAsBytes(List.of(input("upsert", "After", "Updated", 300))));
        assertEquals(0, result.added());
        assertEquals(1, result.updated());
        assertEquals(id, id("upsert"));
        var item = catalog.item(id, carts.view(cartId));
        assertEquals("After", item.title());
        assertEquals(2, item.count());
        assertEquals(600, carts.view(cartId).total());
        carts.change(cartId, id, CartAction.MINUS);
        carts.change(cartId, id, CartAction.DELETE);
        assertTrue(carts.view(cartId).items().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sku\":\"second\",\"title\":\"Second\",\"description\":\"D\",\"image\":\"CUP\",\"price\":12.9}",
            "{\"sku\":\"second\",\"title\":\" \",\"description\":\"D\",\"image\":\"CUP\",\"price\":12}",
            "{\"sku\":\"atomic\",\"title\":\"Duplicate\",\"description\":\"D\",\"image\":\"CUP\",\"price\":12}",
            "{\"sku\":\"second\",\"title\":\"Second\",\"description\":\"D\",\"image\":\"../../secret\",\"price\":12}"
    })
    void invalidCatalogRejectsTheWholeFileBeforeWritingItsValidFirstRow(String invalid) {
        String first = """
                {"sku":"atomic","title":"First","description":"D","image":"KEYBOARD","price":100}
                """;
        byte[] input = ("[" + first + "," + invalid + "]").getBytes(StandardCharsets.UTF_8);
        assertThrows(StoreException.class, () -> importer.importJson(input));
        assertTrue(products.findAllBySkuIn(List.of("atomic")).isEmpty());
    }

    @Test
    void checkoutCreatesOwnedOrderAndDeliveryThenClearsTheCart() throws Exception {
        importer.importJson(json.writeValueAsBytes(List.of(input("checkout", "Keyboard", "Description", 2500))));
        UUID cartId = carts.identify(null);
        long productId = id("checkout");
        carts.change(cartId, productId, CartAction.PLUS);
        carts.change(cartId, productId, CartAction.PLUS);

        long orderId = checkout.buy(cartId);
        var order = orderService.get(orderId, cartId);
        assertEquals(5000, order.totalSum());
        assertEquals(2, order.items().getFirst().count());
        assertTrue(carts.view(cartId).items().isEmpty());
        assertEquals(1, outbox.countByDeliveredAtIsNull());
        assertEquals(StoreError.ORDER_NOT_FOUND,
                assertThrows(StoreException.class, () -> orderService.get(orderId, UUID.randomUUID())).getError());
        assertEquals(StoreError.EMPTY_CART, assertThrows(StoreException.class, () -> checkout.buy(cartId)).getError());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedOutboxWriteRollsBackTheOrderAndCartDeletion() throws Exception {
        importer.importJson(json.writeValueAsBytes(List.of(input("rollback", "Rollback", "Description", 700))));
        UUID cartId = carts.identify(null);
        carts.change(cartId, id("rollback"), CartAction.PLUS);
        // Ошибка на последней записи проверяет настоящий rollback сервисной транзакции, а не откат теста.
        jdbc.execute("ALTER TABLE analytics_outbox ADD CONSTRAINT reject_delivery_test CHECK (order_id < 0)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> checkout.buy(cartId));
            assertEquals(700, carts.view(cartId).total());
            assertTrue(orderService.list(cartId).isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE analytics_outbox DROP CONSTRAINT reject_delivery_test");
            clean(cartId, "rollback");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousCheckoutCreatesExactlyOneCommittedOrder() throws Exception {
        importer.importJson(json.writeValueAsBytes(List.of(input("concurrent", "Concurrent", "Description", 700))));
        UUID cartId = carts.identify(null);
        carts.change(cartId, id("concurrent"), CartAction.PLUS);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(workers.submit(() -> buyAfter(start, cartId)),
                    workers.submit(() -> buyAfter(start, cartId)));
            start.countDown();
            int successful = 0;
            for (var future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    successful++;
                }
            }
            assertEquals(1, successful);
            assertEquals(1, orders.findByCartIdOrderByCreatedAtDescIdDesc(cartId).size());
            assertTrue(carts.view(cartId).items().isEmpty());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            clean(cartId, "concurrent");
        }
    }

    private boolean buyAfter(CountDownLatch start, UUID cartId) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Checkout workers did not start");
        }
        try {
            checkout.buy(cartId);
            return true;
        } catch (StoreException exception) {
            assertEquals(StoreError.EMPTY_CART, exception.getError());
            return false;
        }
    }

    private void clean(UUID cartId, String sku) {
        // Независимые транзакции потоков не откатываются аннотацией тестового метода.
        jdbc.update("DELETE FROM purchase_orders WHERE cart_id = ?", cartId);
        jdbc.update("DELETE FROM shopping_carts WHERE id = ?", cartId);
        jdbc.update("DELETE FROM products WHERE sku = ?", sku);
    }

    private long id(String sku) {
        return products.findAllBySkuIn(List.of(sku)).getFirst().getId();
    }

    private ProductInput input(String sku, String title, String description, long price) {
        return new ProductInput(sku, title, description, ImageAsset.KEYBOARD, price);
    }
}
