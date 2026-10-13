package dev.principalwater.store.dao;

import dev.principalwater.store.model.AnalyticsOutbox;
import dev.principalwater.store.model.CartLine;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.OrderView;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.ProductInput;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.ShoppingCart;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StoreRepositoryTest {
    @Autowired private ProductRepository products;
    @Autowired private CartRepository carts;
    @Autowired private CartLineRepository cartLines;
    @Autowired private OrderRepository orders;
    @Autowired private OutboxRepository outbox;
    @Autowired private EntityManager entityManager;

    @Test
    void persistedOrderRetainsItsPriceAndTitleAfterACatalogUpdate() {
        var product = products.save(input("Original keyboard", 2500));
        var cart = carts.save(new ShoppingCart(UUID.randomUUID()));
        var line = new CartLine(cart, product);
        line.increment();
        cartLines.save(line);
        var order = orders.save(new PurchaseOrder(cart, List.of(line), Instant.parse("2026-10-12T10:00:00Z")));
        long orderId = order.getId();
        long productId = product.getId();
        entityManager.flush();
        entityManager.clear();

        products.findById(productId).orElseThrow().update(new ProductInput(
                "keyboard", "Renamed keyboard", "New description", ImageAsset.CUP, 9000L));
        entityManager.flush();
        entityManager.clear();

        var saved = OrderView.from(orders.findByIdAndCartId(orderId, cart.getId()).orElseThrow());
        var savedLine = saved.items().getFirst();
        assertEquals(5000, saved.totalSum());
        assertEquals(productId, savedLine.id());
        assertEquals("Original keyboard", savedLine.title());
        assertEquals("/images/keyboard.svg", savedLine.imgPath());
        assertEquals(2500, savedLine.price());
        assertEquals(2, savedLine.count());
        assertEquals(5000, savedLine.total());
        assertTrue(orders.findByIdAndCartId(orderId, UUID.randomUUID()).isEmpty());
    }

    @Test
    void pendingDeliverySurvivesFlushAndIsRemovedOnlyByAcknowledgement() {
        var product = products.save(input("Delivery keyboard", 2500));
        var cart = carts.save(new ShoppingCart(UUID.randomUUID()));
        var line = cartLines.save(new CartLine(cart, product));
        var order = orders.save(new PurchaseOrder(cart, List.of(line), Instant.parse("2026-10-12T10:00:00Z")));
        outbox.save(new AnalyticsOutbox(order));
        entityManager.flush();
        entityManager.clear();

        assertEquals(List.of(order.getId()), outbox.pendingOrderIds(PageRequest.of(0, 100)));
        assertEquals(1, outbox.countByDeliveredAtIsNull());
        assertEquals(1, outbox.acknowledge(List.of(order.getId()), Instant.parse("2026-10-12T10:01:00Z")));
        entityManager.flush();
        entityManager.clear();
        assertTrue(outbox.pendingOrderIds(PageRequest.of(0, 100)).isEmpty());
        assertEquals(0, outbox.countByDeliveredAtIsNull());
    }

    private Product input(String title, long price) {
        return new Product(new ProductInput("keyboard", title, "Description", ImageAsset.KEYBOARD, price));
    }
}
