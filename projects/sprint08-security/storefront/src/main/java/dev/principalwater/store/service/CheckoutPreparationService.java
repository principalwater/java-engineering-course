package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartQueries;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.CheckoutIntentLineRepository;
import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.payment.CheckoutIntent;
import dev.principalwater.store.payment.CheckoutIntentLine;
import dev.principalwater.store.payment.CheckoutSnapshot;
import dev.principalwater.store.util.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CheckoutPreparationService {
    private final CartRepository carts;
    private final CartQueries queries;
    private final CheckoutIntentRepository intents;
    private final CheckoutIntentLineRepository snapshots;
    private final Clock clock;
    private final TransactionalOperator transactions;

    public CheckoutPreparationService(CartRepository carts, CartQueries queries, CheckoutIntentRepository intents,
                                      CheckoutIntentLineRepository snapshots, Clock clock, TransactionalOperator transactions) {
        this.carts = carts;
        this.queries = queries;
        this.intents = intents;
        this.snapshots = snapshots;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Mono<CheckoutIntent> prepare(UUID cartId, UUID payerId, UUID key, long amountMinor) {
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
}
