package dev.principalwater.store.payment;

import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.service.CheckoutService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PaymentRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentRecovery.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final CheckoutIntentRepository intents;
    private final CheckoutService checkout;
    public PaymentRecovery(CheckoutIntentRepository intents, CheckoutService checkout) {
        this.intents = intents; this.checkout = checkout;
    }
    @Scheduled(fixedDelayString = "${store.payments.recovery-delay:15000}")
    public Mono<Void> recover() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) { return Mono.empty(); }
            // В БД выбирается не более 16 записей; HTTP последователен и не удерживает SQL connection.
            return intents.pending().collectList().flatMapMany(reactor.core.publisher.Flux::fromIterable).concatMap(intent -> checkout.resume(intent).onErrorResume(failure -> {
                LOG.warn("Payment recovery deferred for intent {}", intent.id());
                return Mono.empty();
            })).then().doFinally(signal -> running.set(false));
        });
    }
}
