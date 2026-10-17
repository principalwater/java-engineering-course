package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartQueries;
import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.payment.CheckoutAvailability;
import dev.principalwater.store.payment.CheckoutIntent;
import dev.principalwater.store.payment.PaymentsGateway;
import dev.principalwater.store.payment.WalletService;
import dev.principalwater.store.util.Money;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class CheckoutService {
    private final CartQueries queries;
    private final StoreUserRepository users;
    private final CheckoutIntentRepository intents;
    private final PaymentsGateway payments;
    private final WalletService wallets;
    private final CheckoutPreparationService preparation;
    private final CheckoutSettlementService settlement;

    public CheckoutService(CartQueries queries, StoreUserRepository users, CheckoutIntentRepository intents,
                           PaymentsGateway payments, WalletService wallets, CheckoutPreparationService preparation,
                           CheckoutSettlementService settlement) {
        this.queries = queries;
        this.users = users;
        this.intents = intents;
        this.payments = payments;
        this.wallets = wallets;
        this.preparation = preparation;
        this.settlement = settlement;
    }

    public Mono<CheckoutAvailability> availability(UUID cartId) {
        return payer(cartId).flatMap(wallets::balance).flatMap(wallet ->
                intents.findByActiveCartId(cartId).map(intent -> CheckoutAvailability.from(wallet, intent.amountMinor(), true, intent.id()))
                        .switchIfEmpty(queries.snapshot(cartId).map(snapshot -> CheckoutAvailability.from(wallet,
                                Money.minor(snapshot.totalRubles()), false, snapshot.key()))));
    }

    public Mono<Long> buy(UUID cartId, UUID checkoutKey, long amountMinor) {
        // HTTP выполняется только после commit короткой SQL-транзакции подготовки снимка.
        return payer(cartId).flatMap(payerId -> preparation.prepare(cartId, payerId, checkoutKey, amountMinor))
                .flatMap(this::resume);
    }

    public Mono<Long> resume(CheckoutIntent intent) {
        if (intent.status() == CheckoutIntent.Status.CONFIRMED) { return Mono.just(intent.orderId()); }
        if (intent.status() == CheckoutIntent.Status.DECLINED) { return Mono.error(new StoreException(StoreError.INSUFFICIENT_FUNDS)); }
        return payments.debit(intent)
                // Неизвестный исход не равен отказу: intent и снимок остаются для того же ключа повтора.
                .onErrorMap(failure -> new StoreException(StoreError.PAYMENT_PENDING, failure))
                .flatMap(receipt -> settlement.settle(intent, receipt));
    }

    private Mono<UUID> payer(UUID cartId) {
        return users.findByCartId(cartId).map(StoreUser::id).switchIfEmpty(Mono.error(new StoreException(StoreError.CART_NOT_FOUND)));
    }
}
