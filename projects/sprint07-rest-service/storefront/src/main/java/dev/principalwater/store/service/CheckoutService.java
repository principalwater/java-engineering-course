package dev.principalwater.store.service;

import dev.principalwater.store.dao.*;
import dev.principalwater.store.model.*;
import dev.principalwater.store.payment.*;
import dev.principalwater.store.payment.generated.model.PaymentReceipt;
import dev.principalwater.store.util.Money;
import dev.principalwater.store.util.VisitorIdentity;
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
    private final StoreUserRepository users;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final OutboxRepository outbox;
    private final CheckoutIntentRepository intents;
    private final CheckoutIntentLineRepository snapshots;
    private final PaymentsGateway payments;
    private final WalletService wallets;
    private final Clock clock;
    private final TransactionalOperator transactions;

    public CheckoutService(CartRepository carts, CartLineRepository cartLines, CartQueries queries,
                           StoreUserRepository users, OrderRepository orders, OrderLineRepository orderLines,
                           OutboxRepository outbox, CheckoutIntentRepository intents, CheckoutIntentLineRepository snapshots,
                           PaymentsGateway payments, WalletService wallets, Clock clock, TransactionalOperator transactions) {
        this.carts = carts; this.cartLines = cartLines; this.queries = queries; this.users = users;
        this.orders = orders; this.orderLines = orderLines; this.outbox = outbox; this.intents = intents;
        this.snapshots = snapshots; this.payments = payments; this.wallets = wallets;
        this.clock = clock; this.transactions = transactions;
    }

    public Mono<CheckoutAvailability> availability(UUID cartId) {
        return payer(cartId).flatMap(wallets::balance).flatMap(wallet ->
                intents.findByActiveCartId(cartId).map(intent -> CheckoutAvailability.from(wallet, intent.amountMinor(), true, intent.id()))
                        .switchIfEmpty(queries.snapshot(cartId).map(snapshot -> CheckoutAvailability.from(wallet,
                                Money.minor(snapshot.totalRubles()), false, snapshot.key()))));
    }

    public Mono<Long> buy(UUID cartId, UUID checkoutKey, long amountMinor) {
        // HTTP выполняется только после commit короткой SQL-транзакции подготовки снимка.
        return payer(cartId).flatMap(payerId -> prepare(cartId, payerId, checkoutKey, amountMinor)).flatMap(this::resume);
    }

    public Mono<Long> resume(CheckoutIntent intent) {
        if (intent.status() == CheckoutIntent.Status.CONFIRMED) { return Mono.just(intent.orderId()); }
        if (intent.status() == CheckoutIntent.Status.DECLINED) { return Mono.error(new StoreException(StoreError.INSUFFICIENT_FUNDS)); }
        return payments.debit(intent)
                // Неизвестный исход не равен отказу: intent и снимок остаются для того же ключа повтора.
                .onErrorMap(failure -> new StoreException(StoreError.PAYMENT_PENDING))
                .flatMap(receipt -> receipt.getStatus() == PaymentReceipt.StatusEnum.SUCCEEDED
                        ? confirm(intent, receipt) : decline(intent));
    }

    private Mono<UUID> payer(UUID cartId) {
        return users.findByCartId(cartId).map(StoreUser::id).defaultIfEmpty(VisitorIdentity.analyticsId(cartId));
    }

    private Mono<CheckoutIntent> prepare(UUID cartId, UUID payerId, UUID key, long amountMinor) {
        return Mono.defer(() -> carts.lockById(cartId)
                .switchIfEmpty(Mono.error(new StoreException(StoreError.CART_NOT_FOUND)))
                .then(intents.findById(key).filter(intent -> cartId.equals(intent.cartId()))
                        .flatMap(intent -> intent.amountMinor() == amountMinor ? Mono.just(intent)
                                : Mono.error(new StoreException(StoreError.STALE_CHECKOUT))))
                .switchIfEmpty(Mono.defer(() -> intents.findByActiveCartId(cartId)
                        .flatMap(other -> Mono.<CheckoutIntent>error(new StoreException(StoreError.CHECKOUT_IN_PROGRESS)))
                        .switchIfEmpty(queries.snapshot(cartId).flatMap(snapshot -> {
                            if (!snapshot.key().equals(key) || Money.minor(snapshot.totalRubles()) != amountMinor) { return Mono.error(new StoreException(StoreError.STALE_CHECKOUT)); }
                            if (snapshot.items().isEmpty()) { return Mono.error(new StoreException(StoreError.EMPTY_CART)); }
                            return createIntent(cartId, payerId, snapshot);
                        }))))).as(transactions::transactional);
    }

    private Mono<CheckoutIntent> createIntent(UUID cartId, UUID payerId, CheckoutSnapshot snapshot) {
        var intent = new CheckoutIntent(snapshot.key(), cartId, payerId, Money.minor(snapshot.totalRubles()),
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return intents.save(intent).flatMap(saved -> Flux.range(0, snapshot.items().size())
                .concatMap(index -> snapshots.save(new CheckoutIntentLine(saved.id(), index, snapshot.items().get(index))))
                .then(Mono.just(saved)));
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
