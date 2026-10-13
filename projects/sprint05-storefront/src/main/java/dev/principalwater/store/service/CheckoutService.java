package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartLineRepository;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.model.AnalyticsOutbox;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private final CartRepository carts;
    private final CartLineRepository lines;
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final Clock clock;

    public CheckoutService(CartRepository carts, CartLineRepository lines, OrderRepository orders,
                           OutboxRepository outbox, Clock clock) {
        this.carts = carts;
        this.lines = lines;
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public long buy(UUID cartId) {
        // Та же блокировка используется изменением корзины: повторный checkout не создаёт второй заказ.
        var cart = carts.lockById(cartId).orElseThrow(() -> new StoreException(StoreError.CART_NOT_FOUND));
        var items = lines.findByCartIdOrderById(cartId);
        var order = orders.save(new PurchaseOrder(cart, items, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        // Сеть ClickHouse не участвует в транзакции покупки; здесь фиксируется только долговечная очередь.
        outbox.save(new AnalyticsOutbox(order));
        lines.deleteAll(items);
        return order.getId();
    }
}
