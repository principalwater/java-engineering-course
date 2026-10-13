package dev.principalwater.store.dao;

import dev.principalwater.store.model.AnalyticsOutbox;
import dev.principalwater.store.model.CartItem;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.OrderLine;
import dev.principalwater.store.model.OrderView;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.ProductInput;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.ShoppingCart;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataR2dbcTest
@ActiveProfiles("test")
class StoreRepositoryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Instant PURCHASE_TIME = Instant.parse("2026-10-12T10:00:00.123Z");
    @Autowired private ProductRepository products;
    @Autowired private CartRepository carts;
    @Autowired private OrderRepository orders;
    @Autowired private OrderLineRepository lines;
    @Autowired private OutboxRepository outbox;
    @Autowired private DatabaseClient database;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        // ReactiveCrudRepository не участвует в test-managed JDBC rollback: очищаем только отдельную тестовую БД.
        Flux.just("analytics_outbox", "order_items", "purchase_orders", "cart_items", "shopping_carts", "products")
                .concatMap(table -> database.sql("DELETE FROM " + table).fetch().rowsUpdated()).then().block(TIMEOUT);
    }

    @Test
    void persistedOrderRetainsItsPriceAndTitleAfterACatalogUpdate() {
        var product = await(products.save(input("Original keyboard", 2500)));
        var cart = await(carts.save(new ShoppingCart(UUID.randomUUID())));
        var item = new CartItem(product, 2);
        var order = await(orders.save(new PurchaseOrder(cart.id(), List.of(item), PURCHASE_TIME)));
        await(lines.save(new OrderLine(order.id(), 0, item)));
        await(products.save(product.update(new ProductInput(
                "keyboard", "Renamed keyboard", "New description", ImageAsset.CUP, 9000L))));

        var loaded = orders.findByIdAndCartId(order.id(), cart.id()).flatMap(saved ->
                lines.findByOrderIdOrderByLineNumber(saved.id()).collectList().map(items -> OrderView.from(saved, items)));
        StepVerifier.create(loaded).assertNext(saved -> {
            var savedLine = saved.items().getFirst();
            assertTrue(saved.id() > 0);
            assertEquals(PURCHASE_TIME, saved.createdAt());
            assertEquals(5000, saved.totalSum());
            assertEquals(product.id().longValue(), savedLine.id());
            assertEquals("Original keyboard", savedLine.title());
            assertEquals("/images/keyboard.svg", savedLine.imgPath());
            assertEquals(2500, savedLine.price());
            assertEquals(2, savedLine.count());
            assertEquals(5000, savedLine.total());
        }).expectComplete().verify(TIMEOUT);
        StepVerifier.create(orders.findByIdAndCartId(order.id(), UUID.randomUUID()))
                .expectComplete().verify(TIMEOUT);
    }

    @Test
    void pendingDeliverySurvivesReloadAndIsRemovedOnlyByAcknowledgement() {
        var product = await(products.save(input("Delivery keyboard", 2500)));
        var cart = await(carts.save(new ShoppingCart(UUID.randomUUID())));
        var order = await(orders.save(new PurchaseOrder(cart.id(), List.of(new CartItem(product, 1)), PURCHASE_TIME)));
        await(outbox.save(new AnalyticsOutbox(order.id())));

        StepVerifier.create(outbox.pendingOrderIds(100)).expectNext(order.id()).expectComplete().verify(TIMEOUT);
        StepVerifier.create(outbox.countByDeliveredAtIsNull()).expectNext(1L).expectComplete().verify(TIMEOUT);
        var delivered = Instant.parse("2026-10-12T10:01:00.456Z");
        StepVerifier.create(outbox.acknowledge(List.of(order.id()), delivered))
                .expectNext(1L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(outbox.pendingOrderIds(100)).expectComplete().verify(TIMEOUT);
        StepVerifier.create(outbox.countByDeliveredAtIsNull()).expectNext(0L).expectComplete().verify(TIMEOUT);
        StepVerifier.create(outbox.latestDelivery().map(OffsetDateTime::toInstant)).expectNext(delivered).expectComplete().verify(TIMEOUT);
    }

    private <T> T await(Mono<T> publisher) {
        return Objects.requireNonNull(publisher.block(TIMEOUT), "Expected a persisted row");
    }

    private Product input(String title, long price) {
        return new Product(new ProductInput("keyboard", title, "Description", ImageAsset.KEYBOARD, price));
    }
}
