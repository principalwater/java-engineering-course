package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartLineRepository;
import dev.principalwater.store.dao.CartQueries;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.OrderLineRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.model.AnalyticsOutbox;
import dev.principalwater.store.model.OrderLine;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CheckoutService {
    private final CartRepository carts;
    private final CartLineRepository cartLines;
    private final CartQueries queries;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final OutboxRepository outbox;
    private final Clock clock;
    private final TransactionalOperator transactions;

    public CheckoutService(CartRepository carts, CartLineRepository cartLines, CartQueries queries,
                           OrderRepository orders, OrderLineRepository orderLines, OutboxRepository outbox,
                           Clock clock, TransactionalOperator transactions) {
        this.carts = carts;
        this.cartLines = cartLines;
        this.queries = queries;
        this.orders = orders;
        this.orderLines = orderLines;
        this.outbox = outbox;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Mono<Long> buy(UUID cartId) {
        return Mono.defer(() -> carts.lockById(cartId)
                .switchIfEmpty(Mono.error(new StoreException(StoreError.CART_NOT_FOUND)))
                .then(queries.items(cartId).collectList())
                .flatMap(items -> orders.save(new PurchaseOrder(cartId, items,
                        clock.instant().truncatedTo(ChronoUnit.MILLIS))).flatMap(order ->
                        // Один connection выполняет записи последовательно; cancel откатывает весь заказ.
                        Flux.range(0, items.size()).concatMap(index ->
                                orderLines.save(new OrderLine(order.id(), index, items.get(index))))
                                .then(outbox.save(new AnalyticsOutbox(order.id())))
                                .then(cartLines.deleteByCartId(cartId)).thenReturn(order.id())))
                .as(transactions::transactional));
    }
}
