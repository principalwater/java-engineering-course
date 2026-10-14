package dev.principalwater.store.dao;

import dev.principalwater.store.DatabaseTestBase;
import dev.principalwater.store.model.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import reactor.test.StepVerifier;

import static dev.principalwater.store.ProductFixtures.productInput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataR2dbcTest
class StoreRepositoryTest extends DatabaseTestBase {
    private static final Instant PURCHASE_TIME = Instant.parse("2026-10-12T10:00:00.123Z");
    @Autowired private ProductRepository products;
    @Autowired private CartRepository carts;
    @Autowired private OrderRepository orders;
    @Autowired private OrderLineRepository lines;
    @Autowired private OutboxRepository outbox;

    @Test
    void persistedOrderRetainsItsPriceAndTitleAfterACatalogUpdate() {
        var product = await(products.save(new Product(productInput(
                "keyboard", "Original keyboard", "Description", 2500))));
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
        var product = await(products.save(new Product(productInput(
                "keyboard", "Delivery keyboard", "Description", 2500))));
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
}
