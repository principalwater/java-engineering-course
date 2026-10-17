package dev.principalwater.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.PaymentsStub;
import dev.principalwater.store.StoreIntegrationTestBase;
import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.OrderHistoryPage;
import dev.principalwater.store.model.OrderLineView;
import dev.principalwater.store.model.OrderStatus;
import dev.principalwater.store.model.OrderView;
import dev.principalwater.store.model.ProductInput;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.payment.CheckoutIntent;
import dev.principalwater.store.payment.PaymentRecovery;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static dev.principalwater.store.ProductFixtures.productInput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreServiceTest extends StoreIntegrationTestBase {
    @Autowired private CatalogImportService importer;
    @Autowired private CatalogService catalog;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OutboxRepository outbox;
    @Autowired private CheckoutIntentRepository intents;
    @Autowired private PaymentRecovery recovery;
    @Autowired private ObjectMapper json;

    @Test
    void orderHistoryUsesStableOwnerScopedKeysetsAcrossEqualAndDifferentTimestamps() throws Exception {
        importItems(productInput("history", "History", "Cursor fixture", 100));
        long product = id("history");
        UUID owner = createAccountCart();
        UUID other = createAccountCart();
        var created = new ArrayList<Long>();
        for (int index = 0; index < 5; index++) {
            await(carts.change(index == 1 ? other : owner, product, CartAction.PLUS));
            created.add(await(buy(index == 1 ? other : owner)));
        }
        // Первый заказ владельца новее остальных при наименьшем id, последний старее при наибольшем: порядок
        // id не совпадает с порядком времени, а второй и третий делят время и проверяют ветку равенства.
        createdAt(created.get(0), "2026-10-15 12:00:02+00");
        createdAt(created.get(1), "2026-10-15 12:00:01+00");
        createdAt(created.get(2), "2026-10-15 12:00:01+00");
        createdAt(created.get(3), "2026-10-15 12:00:01+00");
        createdAt(created.get(4), "2026-10-15 12:00:00+00");
        var first = await(orderService.history(owner, 2, null));
        assertEquals(List.of(created.get(0), created.get(3)), first.orders().stream().map(OrderView::id).toList());
        var last = await(orderService.history(owner, 2, first.nextCursor()));
        assertEquals(List.of(created.get(2), created.get(4)), last.orders().stream().map(OrderView::id).toList());
        assertNull(last.nextCursor());
        assertThrows(StoreException.class, () -> orderService.history(other, 2, first.nextCursor()));
        var foreign = await(orderService.history(other, 2, null));
        assertEquals(List.of(created.get(1)), foreign.orders().stream().map(OrderView::id).toList());
        assertEquals(1, foreign.orders().getFirst().items().size());
        assertThrows(StoreException.class, () -> orderService.history(owner, 3, null));
        assertThrows(StoreException.class, () -> orderService.history(owner, 2, "malformed-cursor"));
    }

    @Test
    void catalogSupportsLiteralDescriptionSearchStableSortingAndPagination() throws Exception {
        importItems(productInput("alpha", "Alpha", "First", 20), productInput("beta", "Beta", "Second", 10),
                productInput("gamma", "Beta", "C++ & blue", 10), productInput("zeta", "Zeta", "Literal 100%_! fragment", 30));
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
        importItems(productInput("upsert", "Before", "Description", 100));
        long id = id("upsert");
        UUID cartId = createAccountCart();
        await(carts.change(cartId, id, CartAction.PLUS));
        await(carts.change(cartId, id, CartAction.PLUS));

        StepVerifier.create(importer.importJson(json.writeValueAsBytes(List.of(productInput("upsert", "After", "Updated", 300)))))
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
    @MethodSource("invalidCatalogItems")
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
        importItems(productInput("checkout", "Keyboard", "Description", 2500));
        UUID cartId = createAccountCart();
        long productId = id("checkout");
        await(carts.change(cartId, productId, CartAction.SET, 2));

        UUID purchaseKey = UUID.fromString(await(checkout.availability(cartId)).checkoutKey());
        long orderId = await(checkout.buy(cartId, purchaseKey, 500000));
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
        assertEquals(orderId, await(checkout.buy(cartId, purchaseKey, 500000)), "A replay must return the same confirmed order");
        assertEquals(1, PaymentsStub.DEBITS.get());
    }

    @Test
    void failedOutboxWriteRollsBackTheOrderAndCartDeletion() throws Exception {
        importItems(productInput("rollback", "Rollback", "Description", 700));
        UUID cartId = createAccountCart();
        await(carts.change(cartId, id("rollback"), CartAction.PLUS));
        // Ошибка последней записи проверяет настоящий rollback сервисной транзакции, а не откат теста.
        execute("ALTER TABLE analytics_outbox ADD CONSTRAINT reject_delivery_test CHECK (order_id < 0)");
        try {
            StepVerifier.create(buy(cartId)).expectErrorSatisfies(failure -> {
                assertStoreError(StoreError.PAYMENT_PENDING, failure);
                assertInstanceOf(DataIntegrityViolationException.class, failure.getCause());
            }).verify(TIMEOUT);
            StepVerifier.create(carts.view(cartId)).assertNext(view -> assertEquals(700, view.total()))
                    .expectComplete().verify(TIMEOUT);
            StepVerifier.create(orderService.history(cartId, 100, null).map(OrderHistoryPage::orders)).assertNext(selected -> assertTrue(selected.isEmpty()))
                    .expectComplete().verify(TIMEOUT);
        } finally {
            execute("ALTER TABLE analytics_outbox DROP CONSTRAINT reject_delivery_test");
        }
        assertEquals(1, PaymentsStub.DEBITS.get());
        long recovered = await(buy(cartId));
        assertEquals(OrderStatus.PAID, await(orderService.get(recovered, cartId)).status());
        assertEquals(1, PaymentsStub.DEBITS.get(), "Recovery must not repeat the remote debit");
    }

    @Test
    void simultaneousCheckoutCreatesExactlyOneCommittedOrder() throws Exception {
        importItems(productInput("concurrent", "Concurrent", "Description", 700));
        UUID cartId = createAccountCart();
        await(carts.change(cartId, id("concurrent"), CartAction.PLUS));

        UUID key = UUID.fromString(await(checkout.availability(cartId)).checkoutKey());
        var results = race(checkout.buy(cartId, key, 70000).thenReturn(true), checkout.buy(cartId, key, 70000).thenReturn(true));
        assertEquals(2, results.stream().filter(Signal::isOnNext).count());
        assertEquals(1, PaymentsStub.DEBITS.get());
        // База очищается перед тестом, а корзина здесь одна: общий счётчик равен числу её заказов.
        StepVerifier.create(orders.count()).expectNext(1L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(carts.view(cartId)).assertNext(view -> assertTrue(view.items().isEmpty()))
                .expectComplete().verify(TIMEOUT);
    }

    @Test
    void simultaneousIncrementsCannotExceedTheQuantityLimit() throws Exception {
        importItems(productInput("limit", "Limited", "Description", 700));
        UUID cartId = createAccountCart();
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
        importItems(productInput("race", "Race", "Description", 700));
        UUID cartId = createAccountCart();
        long productId = id("race");
        await(carts.change(cartId, productId, CartAction.PLUS));

        UUID key = UUID.fromString(await(checkout.availability(cartId)).checkoutKey());
        var results = race(checkout.buy(cartId, key, 70000).thenReturn(true),
                carts.change(cartId, productId, CartAction.PLUS).thenReturn(true));
        assertTrue(results.stream().noneMatch(signal -> signal.isOnError()
                && (!(signal.getThrowable() instanceof StoreException failure)
                || failure.getError() != StoreError.CHECKOUT_IN_PROGRESS && failure.getError() != StoreError.STALE_CHECKOUT)));
        long rejectedIncrement = results.stream().filter(signal -> signal.isOnError()
                && ((StoreException) signal.getThrowable()).getError() == StoreError.CHECKOUT_IN_PROGRESS).count();
        var purchased = await(orderService.history(cartId, 100, null).map(OrderHistoryPage::orders));
        int orderedQuantity = purchased.stream().flatMap(order -> order.items().stream()).mapToInt(OrderLineView::count).sum();
        assertEquals(2, orderedQuantity + await(carts.view(cartId)).itemCount() + rejectedIncrement);
    }

    @Test
    void setRequiresAQuantityAndZeroRemovesTheExistingLine() throws Exception {
        importItems(productInput("set", "Set", "Description", 700));
        UUID cartId = createAccountCart();
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
        importItems(productInput("quantity", "Quantity", "Description", 700));
        UUID cartId = createAccountCart();
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

    @Test
    void lostResponseFreezesTheSnapshotAndRecoversWithoutAnotherDebit() throws Exception {
        importItems(productInput("uncertain", "Uncertain", "Description", 700));
        UUID cartId = createAccountCart();
        long productId = id("uncertain");
        await(carts.change(cartId, productId, CartAction.PLUS));
        PaymentsStub.LOSE_RESPONSE.set(true);
        expectError(buy(cartId), StoreError.PAYMENT_PENDING);
        assertEquals(1, PaymentsStub.DEBITS.get());
        assertTrue(await(orderService.history(cartId, 100, null).map(OrderHistoryPage::orders)).isEmpty());
        assertTrue(await(checkout.availability(cartId)).getPending());
        expectError(carts.change(cartId, productId, CartAction.PLUS), StoreError.CHECKOUT_IN_PROGRESS);
        // Импорт меняет текущую цену; повтор оплачивает прежний SQL-снимок, а не Redis или новую цену.
        importItems(productInput("uncertain", "Updated", "Description", 900));
        long orderId = await(buy(cartId));
        assertEquals(700, await(orderService.get(orderId, cartId)).totalSum());
        assertEquals(1, PaymentsStub.DEBITS.get());
        assertEquals(1L, await(outbox.countByDeliveredAtIsNull()));
    }

    @Test
    void recoveryReachesLaterPaymentsWhenTheOldestBatchRemainsUnavailable() throws Exception {
        importItems(productInput("recovery", "Recovery", "Description", 700));
        long productId = id("recovery");
        var prepared = new ArrayList<CheckoutIntent>();
        PaymentsStub.UNAVAILABLE.set(true);
        for (int index = 0; index < 33; index++) {
            UUID cartId = createAccountCart();
            await(carts.change(cartId, productId, CartAction.PLUS));
            expectError(buy(cartId), StoreError.PAYMENT_PENDING);
            prepared.add(await(intents.findByActiveCartId(cartId)));
        }
        // Старше начального grace period: recovery не должен перехватывать исходный debit покупателя.
        execute("UPDATE checkout_intents SET created_at = created_at - INTERVAL '1' MINUTE");
        var blocked = await(intents.pending(java.time.Instant.now(), 32).collectList());
        assertEquals(32, blocked.size());
        blocked.forEach(intent -> PaymentsStub.UNAVAILABLE_KEYS.add(intent.id().toString()));
        var available = prepared.stream().filter(intent -> !PaymentsStub.UNAVAILABLE_KEYS.contains(intent.id().toString()))
                .findFirst().orElseThrow();
        PaymentsStub.UNAVAILABLE.set(false);

        // Старые 32 ошибки не должны навсегда удерживать уже доступную следующую операцию вне очереди.
        recovery.recover().block(TIMEOUT);
        assertEquals(0, PaymentsStub.DEBITS.get());
        recovery.recover().block(TIMEOUT);

        var confirmed = await(intents.findById(available.id()));
        assertEquals(CheckoutIntent.Status.CONFIRMED, confirmed.status());
        assertEquals(available.id(), confirmed.id());
        assertEquals(available.amountMinor(), confirmed.amountMinor());
        assertEquals(700, await(orderService.get(confirmed.orderId(), available.cartId())).totalSum());
        assertTrue(await(carts.view(available.cartId())).items().isEmpty());
        assertEquals(confirmed.orderId(), await(checkout.buy(available.cartId(), available.id(), available.amountMinor())));
        assertEquals(1, PaymentsStub.DEBITS.get(), "Recovery and replay must use the original payment key");
        for (var intent : blocked) {
            assertEquals(CheckoutIntent.Status.PREPARED, await(intents.findById(intent.id())).status());
            assertEquals(700, await(carts.view(intent.cartId())).total());
            assertTrue(await(orderService.history(intent.cartId(), 100, null).map(OrderHistoryPage::orders)).isEmpty());
        }
    }

    @Test
    void unavailableAndInsufficientPaymentsNeverCreateAConfirmedOrder() throws Exception {
        importItems(productInput("unpaid", "Unpaid", "Description", 700));
        UUID cartId = createAccountCart();
        long productId = id("unpaid");
        await(carts.change(cartId, productId, CartAction.PLUS));
        PaymentsStub.funds(accountId(cartId), 100);
        assertFalse(await(checkout.availability(cartId)).getCanBuy());
        expectError(buy(cartId), StoreError.INSUFFICIENT_FUNDS);
        assertEquals(0, PaymentsStub.DEBITS.get());
        assertTrue(await(orderService.history(cartId, 100, null).map(OrderHistoryPage::orders)).isEmpty());
        // Окончательный отказ снимает блокировку корзины; новый checkout получает новый ключ.
        await(carts.change(cartId, productId, CartAction.SET, 2));
        PaymentsStub.UNAVAILABLE.set(true);
        assertFalse(await(checkout.availability(cartId)).getCanBuy());
        expectError(buy(cartId), StoreError.PAYMENT_PENDING);
        assertTrue(await(orderService.history(cartId, 100, null).map(OrderHistoryPage::orders)).isEmpty());
        assertEquals(2, await(carts.view(cartId)).itemCount());
    }

    @Test
    void oldPurchaseReplayCannotChargeNewCartAndStaleQuotesCannotAuthorizeChangedPrices() throws Exception {
        importItems(productInput("quote", "Quote", "Description", 700));
        UUID cartId = createAccountCart();
        long productId = id("quote");
        await(carts.change(cartId, productId, CartAction.PLUS));
        UUID original = UUID.fromString(await(checkout.availability(cartId)).checkoutKey());
        long orderId = await(checkout.buy(cartId, original, 70000));
        await(carts.change(cartId, productId, CartAction.SET, 2));
        assertEquals(orderId, await(checkout.buy(cartId, original, 70000)));
        assertEquals(1, PaymentsStub.DEBITS.get());
        assertEquals(2, await(carts.view(cartId)).itemCount());
        UUID beforeImport = UUID.fromString(await(checkout.availability(cartId)).checkoutKey());
        importItems(productInput("quote", "New price", "Description", 900));
        expectError(checkout.buy(cartId, beforeImport, 140000), StoreError.STALE_CHECKOUT);
        UUID afterImport = UUID.fromString(await(checkout.availability(cartId)).checkoutKey());
        // Даже свежий key вместе со старой суммой SSR-формы не разрешает невидимое покупателю повышение цены.
        expectError(checkout.buy(cartId, afterImport, 140000), StoreError.STALE_CHECKOUT);
        assertEquals(1, PaymentsStub.DEBITS.get());
        assertEquals(1, await(orderService.history(cartId, 100, null).map(OrderHistoryPage::orders)).size());
    }

    static Stream<String> invalidCatalogItems() {
        return Stream.of(
                "{\"sku\":\"second\",\"title\":\"Second\",\"description\":\"D\",\"image\":\"CUP\",\"price\":12.9}",
                "{\"sku\":\"second\",\"title\":\" \",\"description\":\"D\",\"image\":\"CUP\",\"price\":12}",
                "{\"sku\":\"atomic\",\"title\":\"Duplicate\",\"description\":\"D\",\"image\":\"CUP\",\"price\":12}",
                "{\"sku\":\"second\",\"title\":\"Second\",\"description\":\"D\",\"image\":\"../../secret\",\"price\":12}");
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

    private void importItems(ProductInput... inputs) throws Exception {
        await(importer.importJson(json.writeValueAsBytes(List.of(inputs))));
    }

    private long id(String sku) {
        return await(products.findAllBySkuIn(List.of(sku)).single()).id();
    }

    private void createdAt(long orderId, String timestamp) {
        execute("UPDATE purchase_orders SET created_at = TIMESTAMP WITH TIME ZONE '" + timestamp + "' WHERE id = " + orderId);
    }
}
