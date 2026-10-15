package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartLineRepository;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.CheckoutIntentLineRepository;
import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.dao.OrderLineRepository;
import dev.principalwater.store.dao.OrderRepository;
import dev.principalwater.store.dao.OutboxRepository;
import dev.principalwater.store.model.AnalyticsOutbox;
import dev.principalwater.store.model.OrderStatus;
import dev.principalwater.store.model.PurchaseOrder;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.payment.CheckoutIntent;
import dev.principalwater.store.payment.generated.model.PaymentReceipt;
import dev.principalwater.store.util.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
public class CheckoutSettlementService {
    private static final Logger LOG = LoggerFactory.getLogger(CheckoutSettlementService.class);
    private final CartRepository carts;
    private final CartLineRepository cartLines;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final OutboxRepository outbox;
    private final CheckoutIntentRepository intents;
    private final CheckoutIntentLineRepository snapshots;
    private final Clock clock;
    private final TransactionalOperator transactions;

    public CheckoutSettlementService(CartRepository carts, CartLineRepository cartLines, OrderRepository orders,
                                     OrderLineRepository orderLines, OutboxRepository outbox, CheckoutIntentRepository intents,
                                     CheckoutIntentLineRepository snapshots, Clock clock, TransactionalOperator transactions) {
        this.carts = carts;
        this.cartLines = cartLines;
        this.orders = orders;
        this.orderLines = orderLines;
        this.outbox = outbox;
        this.intents = intents;
        this.snapshots = snapshots;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Mono<Long> settle(CheckoutIntent intent, PaymentReceipt receipt) {
        return (receipt.getStatus() == PaymentReceipt.StatusEnum.SUCCEEDED ? confirm(intent, receipt) : decline(intent))
                .onErrorMap(failure -> {
                    if (failure instanceof StoreException) { return failure; }
                    // Откат локального подтверждения не отменяет уже выполненное удалённое списание.
                    LOG.warn("Payment finalization deferred for checkout intent {}", intent.id(), failure);
                    return new StoreException(StoreError.PAYMENT_PENDING, failure);
                });
    }

    private Mono<Long> confirm(CheckoutIntent intent, PaymentReceipt receipt) {
        return carts.lockById(intent.cartId()).then(intents.findById(intent.id())).flatMap(current -> {
            if (current.status() == CheckoutIntent.Status.CONFIRMED) { return Mono.just(current.orderId()); }
            if (current.status() != CheckoutIntent.Status.PREPARED) {
                return Mono.error(new IllegalStateException("Cannot confirm a declined payment intent"));
            }
            var order = new PurchaseOrder(null, current.id(), current.cartId(),
                    clock.instant().truncatedTo(ChronoUnit.MILLIS), Money.rublesExact(current.amountMinor()),
                    OrderStatus.PAID, receipt.getPaymentId());
            return orders.save(order).flatMap(saved -> snapshots.findByIntentIdOrderByLineNumber(current.id())
                    .concatMap(line -> orderLines.save(line.orderLine(saved.id())))
                    .then(outbox.save(new AnalyticsOutbox(saved.id())))
                    .then(cartLines.deleteByCartId(current.cartId())).then(carts.advanceVersion(current.cartId()))
                    .then(intents.confirm(current.id(), saved.id())).flatMap(updated -> updated == 1
                            ? Mono.just(saved.id()) : Mono.error(new IllegalStateException("Checkout intent changed concurrently"))));
        }).as(transactions::transactional);
    }

    private Mono<Long> decline(CheckoutIntent intent) {
        return carts.lockById(intent.cartId()).then(intents.decline(intent.id())).flatMap(changed -> changed == 1
                ? carts.advanceVersion(intent.cartId()) : Mono.just(0)).as(transactions::transactional)
                .then(Mono.error(new StoreException(StoreError.INSUFFICIENT_FUNDS)));
    }
}
