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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class StoreServiceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    @Autowired private CatalogImportService importer;
    @Autowired private CatalogService catalog;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OutboxRepository outbox;
    @Autowired private ObjectMapper json;
    @Autowired private DatabaseClient database;
    @Autowired private ApplicationContext context;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        // R2DBC-транзакции не откатываются test-managed @Transactional: чистим отдельную БД в порядке FK.
        Flux.just("analytics_outbox", "order_items", "purchase_orders", "cart_items", "shopping_carts", "products")
                .concatMap(table -> database.sql("DELETE FROM " + table).fetch().rowsUpdated()).then().block(TIMEOUT);
    }

    @Test
    void catalogSupportsLiteralDescriptionSearchStableSortingAndPagination() throws Exception {
        importItems(input("alpha", "Alpha", "First", 20), input("beta", "Beta", "Second", 10),
                input("gamma", "Beta", "C++ & blue", 10), input("zeta", "Zeta", "Literal 100%_! fragment", 30));
        var empty = new CartView(List.of());

        StepVerifier.create(catalog.list(new CatalogQuery("", SortMode.PRICE, 1, 2), empty)).assertNext(page -> {
            assertEquals(List.of("beta", "gamma"), page.items().stream().map(item -> item.sku()).toList());
            assertTrue(page.paging().hasNext());
            assertEquals(4, page.totalItems());
        }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(catalog.list(new CatalogQuery("", SortMode.ALPHA, 1, 2), empty))
                .assertNext(page -> assertEquals(List.of("alpha", "beta"),
                        page.items().stream().map(item -> item.sku()).toList())).expectComplete().verify(TIMEOUT);
        StepVerifier.create(catalog.list(new CatalogQuery("", SortMode.PRICE, 2, 2), empty))
                .assertNext(page -> assertEquals(List.of("alpha", "zeta"),
                        page.items().stream().map(item -> item.sku()).toList())).expectComplete().verify(TIMEOUT);
        StepVerifier.create(catalog.list(new CatalogQuery("%_!", SortMode.NO, 1, 5), empty))
                .assertNext(page -> assertEquals(List.of("zeta"), page.items().stream().map(item -> item.sku()).toList()))
                .expectComplete().verify(TIMEOUT);
        StepVerifier.create(catalog.list(new CatalogQuery("C++ & blue", SortMode.NO, 1, 5), empty))
                .assertNext(page -> assertEquals(List.of("gamma"), page.items().stream().map(item -> item.sku()).toList()))
                .expectComplete().verify(TIMEOUT);
        StepVerifier.create(catalog.list(new CatalogQuery("", SortMode.NO, 999, 2), empty))
                .assertNext(page -> assertTrue(page.items().isEmpty())).expectComplete().verify(TIMEOUT);
    }

    @Test
    void skuUpsertKeepsTheProductIdentityAndExistingCartQuantity() throws Exception {
        importItems(input("upsert", "Before", "Description", 100));
        long id = id("upsert");
        UUID cartId = await(carts.identify(null));
        await(carts.change(cartId, id, CartAction.PLUS));
        await(carts.change(cartId, id, CartAction.PLUS));

        StepVerifier.create(importer.importJson(json.writeValueAsBytes(List.of(input("upsert", "After", "Updated", 300)))))
                .assertNext(result -> {
                    assertEquals(0, result.added());
                    assertEquals(1, result.updated());
                }).expectComplete().verify(TIMEOUT);
        assertEquals(id, id("upsert"));
        var cart = await(carts.view(cartId));
        StepVerifier.create(catalog.item(id, cart)).assertNext(item -> {
            assertEquals("After", item.title());
            assertEquals(2, item.count());
            assertEquals(600, cart.total());
        }).expectComplete().verify(TIMEOUT);
        await(carts.change(cartId, id, CartAction.MINUS));
        StepVerifier.create(carts.change(cartId, id, CartAction.DELETE))
                .assertNext(view -> assertTrue(view.items().isEmpty())).expectComplete().verify(TIMEOUT);
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
        expectError(importer.importJson(input), StoreError.INVALID_CATALOG);
        StepVerifier.create(products.findAllBySkuIn(List.of("atomic"))).expectComplete().verify(TIMEOUT);
    }

    @Test
    void checkoutCreatesOwnedOrderAndDeliveryThenClearsTheCart() throws Exception {
        importItems(input("checkout", "Keyboard", "Description", 2500));
        UUID cartId = await(carts.identify(null));
        long productId = id("checkout");
        await(carts.change(cartId, productId, CartAction.SET, 2));

        long orderId = await(checkout.buy(cartId));
        StepVerifier.create(orderService.get(orderId, cartId)).assertNext(order -> {
            assertEquals(5000, order.totalSum());
            assertEquals(2, order.items().getFirst().count());
        }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(carts.view(cartId)).assertNext(view -> assertTrue(view.items().isEmpty()))
                .expectComplete().verify(TIMEOUT);
        StepVerifier.create(outbox.countByDeliveredAtIsNull()).expectNext(1L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(orderService.profile(cartId)).assertNext(profile -> {
            assertEquals(1, profile.totalOrders());
            assertEquals(2, profile.units());
            assertEquals(5000, profile.orderValue());
        }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(orderService.profile(UUID.randomUUID())).assertNext(profile -> {
            assertEquals(0, profile.totalOrders());
            assertEquals(0, profile.orderValue());
        }).expectComplete().verify(TIMEOUT);
        expectError(orderService.get(orderId, UUID.randomUUID()), StoreError.ORDER_NOT_FOUND);
        expectError(checkout.buy(cartId), StoreError.EMPTY_CART);
    }

    @Test
    void failedOutboxWriteRollsBackTheOrderAndCartDeletion() throws Exception {
        importItems(input("rollback", "Rollback", "Description", 700));
        UUID cartId = await(carts.identify(null));
        await(carts.change(cartId, id("rollback"), CartAction.PLUS));
        // Ошибка последней записи проверяет настоящий rollback сервисной транзакции, а не откат теста.
        execute("ALTER TABLE analytics_outbox ADD CONSTRAINT reject_delivery_test CHECK (order_id < 0)");
        try {
            StepVerifier.create(checkout.buy(cartId)).expectError(DataIntegrityViolationException.class).verify(TIMEOUT);
            StepVerifier.create(carts.view(cartId)).assertNext(view -> assertEquals(700, view.total()))
                    .expectComplete().verify(TIMEOUT);
            StepVerifier.create(orderService.list(cartId)).assertNext(selected -> assertTrue(selected.isEmpty()))
                    .expectComplete().verify(TIMEOUT);
        } finally {
            execute("ALTER TABLE analytics_outbox DROP CONSTRAINT reject_delivery_test");
        }
    }

    @Test
    void simultaneousCheckoutCreatesExactlyOneCommittedOrder() throws Exception {
        importItems(input("concurrent", "Concurrent", "Description", 700));
        UUID cartId = await(carts.identify(null));
        await(carts.change(cartId, id("concurrent"), CartAction.PLUS));

        var results = race(checkout.buy(cartId).thenReturn(true), checkout.buy(cartId).thenReturn(true));
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        var failure = results.stream().filter(Signal::isOnError).findFirst().orElseThrow();
        assertEquals(StoreError.EMPTY_CART, assertInstanceOf(StoreException.class, failure.getThrowable()).getError());
        StepVerifier.create(orders.findByCartIdOrderByCreatedAtDescIdDesc(cartId).count())
                .expectNext(1L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(carts.view(cartId)).assertNext(view -> assertTrue(view.items().isEmpty()))
                .expectComplete().verify(TIMEOUT);
    }

    @Test
    void simultaneousIncrementsCannotExceedTheQuantityLimit() throws Exception {
        importItems(input("limit", "Limited", "Description", 700));
        UUID cartId = await(carts.identify(null));
        long productId = id("limit");
        await(carts.change(cartId, productId, CartAction.SET, 98));

        var results = race(carts.change(cartId, productId, CartAction.PLUS).thenReturn(true),
                carts.change(cartId, productId, CartAction.PLUS).thenReturn(true));
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        var failure = results.stream().filter(Signal::isOnError).findFirst().orElseThrow();
        assertEquals(StoreError.QUANTITY_LIMIT, assertInstanceOf(StoreException.class, failure.getThrowable()).getError());
        StepVerifier.create(carts.view(cartId)).assertNext(view -> assertEquals(99, view.itemCount()))
                .expectComplete().verify(TIMEOUT);
    }

    @Test
    void checkoutRacingIncrementDoesNotLoseAnyProduct() throws Exception {
        importItems(input("race", "Race", "Description", 700));
        UUID cartId = await(carts.identify(null));
        long productId = id("race");
        await(carts.change(cartId, productId, CartAction.PLUS));

        var results = race(checkout.buy(cartId).thenReturn(true),
                carts.change(cartId, productId, CartAction.PLUS).thenReturn(true));
        assertEquals(2, results.stream().filter(Signal::isOnNext).count());
        var purchased = await(orderService.list(cartId));
        assertEquals(1, purchased.size());
        int orderedQuantity = purchased.getFirst().items().getFirst().count();
        assertEquals(2, orderedQuantity + await(carts.view(cartId)).itemCount());
    }

    @Test
    void setRequiresAQuantityAndZeroRemovesTheExistingLine() throws Exception {
        importItems(input("set", "Set", "Description", 700));
        UUID cartId = await(carts.identify(null));
        long productId = id("set");
        await(carts.change(cartId, productId, CartAction.PLUS));
        expectError(carts.change(cartId, productId, CartAction.SET), StoreError.INVALID_QUANTITY);
        StepVerifier.create(carts.view(cartId)).assertNext(view -> assertEquals(1, view.itemCount()))
                .expectComplete().verify(TIMEOUT);
        StepVerifier.create(carts.change(cartId, productId, CartAction.SET, 0))
                .assertNext(view -> assertTrue(view.items().isEmpty())).expectComplete().verify(TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 100})
    void setRejectsAnOutOfRangeQuantityWithoutChangingTheCart(int quantity) throws Exception {
        importItems(input("quantity", "Quantity", "Description", 700));
        UUID cartId = await(carts.identify(null));
        long productId = id("quantity");
        await(carts.change(cartId, productId, CartAction.SET, 2));
        expectError(carts.change(cartId, productId, CartAction.SET, quantity), StoreError.INVALID_QUANTITY);
        StepVerifier.create(carts.view(cartId)).assertNext(view -> assertEquals(2, view.itemCount()))
                .expectComplete().verify(TIMEOUT);
    }

    @Test
    void applicationUsesOneReactiveTransactionManagerAndNoJdbcDataSource() {
        assertEquals(1, context.getBeansOfType(ReactiveTransactionManager.class).size());
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
    }

    private List<Signal<Boolean>> race(Mono<Boolean> first, Mono<Boolean> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        // materialize сохраняет оба исхода: ошибка одного запроса не отменяет конкурентную транзакцию.
        var result = Flux.merge(startTogether(first, ready, start).materialize(),
                startTogether(second, ready, start).materialize()).collectList().toFuture();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Concurrent operations did not become ready");
            start.countDown();
            return result.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            start.countDown();
            if (!result.isDone()) {
                result.cancel(true);
            }
        }
    }

    private Mono<Boolean> startTogether(Mono<Boolean> operation, CountDownLatch ready, CountDownLatch start) {
        return Mono.defer(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    return Mono.error(new IllegalStateException("Concurrent operation did not start"));
                }
                return operation;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Mono.error(exception);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void expectError(Mono<?> publisher, StoreError error) {
        StepVerifier.create(publisher).expectErrorSatisfies(failure ->
                assertEquals(error, assertInstanceOf(StoreException.class, failure).getError())).verify(TIMEOUT);
    }

    private <T> T await(Mono<T> publisher) {
        return Objects.requireNonNull(publisher.block(TIMEOUT), "Expected a value");
    }

    private void execute(String sql) {
        database.sql(sql).fetch().rowsUpdated().then().block(TIMEOUT);
    }

    private void importItems(ProductInput... inputs) throws Exception {
        await(importer.importJson(json.writeValueAsBytes(List.of(inputs))));
    }

    private long id(String sku) {
        return await(products.findAllBySkuIn(List.of(sku)).single()).id();
    }

    private ProductInput input(String sku, String title, String description, long price) {
        return new ProductInput(sku, title, description, ImageAsset.KEYBOARD, price);
    }
}
