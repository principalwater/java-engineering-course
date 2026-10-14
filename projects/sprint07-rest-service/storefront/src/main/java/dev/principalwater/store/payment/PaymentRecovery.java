package dev.principalwater.store.payment;

import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.service.CheckoutService;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class PaymentRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentRecovery.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final CheckoutIntentRepository intents;
    private final CheckoutService checkout;
    private final Clock clock;
    public PaymentRecovery(CheckoutIntentRepository intents, CheckoutService checkout, Clock clock) {
        this.intents = intents;
        this.checkout = checkout;
        this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${store.payments.recovery-delay:15000}")
    public Mono<Void> recover() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) { return Mono.empty(); }
            // В БД выбирается не более 16 записей; HTTP последователен и не удерживает SQL connection.
            return intents.pending().collectList().flatMapMany(Flux::fromIterable)
                    .concatMap(this::attempt).then().doFinally(signal -> running.set(false));
        });
    }

    private Mono<Long> attempt(CheckoutIntent intent) {
        // Отметка до HTTP не даёт устойчивым ошибкам занять всю очередь; финансовая версия не меняется.
        return intents.markAttempt(intent.id(), clock.instant())
                .flatMap(changed -> changed == 0 ? Mono.empty() : checkout.resume(intent))
                .onErrorResume(failure -> {
                    LOG.warn("Payment recovery deferred for intent {}", intent.id());
                    return Mono.empty();
                });
    }
}
