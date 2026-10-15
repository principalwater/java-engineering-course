package dev.principalwater.store.payment;

import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.service.CheckoutService;
import java.time.Clock;
import java.time.Duration;
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
    private static final int BATCH_SIZE = 32;
    private static final int CONCURRENCY = 2;
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private final AtomicBoolean running = new AtomicBoolean();
    private final CheckoutIntentRepository intents;
    private final CheckoutService checkout;
    private final Clock clock;
    public PaymentRecovery(CheckoutIntentRepository intents, CheckoutService checkout, Clock clock) {
        this.intents = intents;
        this.checkout = checkout;
        this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${store.payments.recovery-delay:1000}")
    public Mono<Void> recover() {
        // Eager cleanup освобождает guard до onComplete, включая немедленную подписку следующего прохода.
        return Mono.using(() -> running.compareAndSet(false, true), acquired -> {
            if (!acquired) { return Mono.empty(); }
            // Освобождаем SQL connection до HTTP; два worker оставляют запас пулам SQL(5)/HTTP(8).
            return intents.pending(clock.instant().minus(RETRY_DELAY), BATCH_SIZE).collectList()
                    .timeout(ATTEMPT_TIMEOUT).onErrorResume(failure -> {
                        LOG.warn("Payment recovery queue scan deferred", failure);
                        return Mono.empty();
                    }).flatMapMany(Flux::fromIterable).flatMap(this::attempt, CONCURRENCY, 1)
                    .then();
        }, acquired -> {
            if (acquired) { running.set(false); }
        });
    }

    private Mono<Long> attempt(CheckoutIntent intent) {
        var now = clock.instant();
        // Первые 30 с от создания intent даём исходному запросу покупателя получить ответ.
        // Claim 30 с > вся попытка 15 с > максимальный debit timeout 10 с; часы реплик синхронизируем.
        // При падении worker запись вновь доступна через 30 с; тот же ключ защищает от повторного списания.
        return intents.markAttempt(intent.id(), now, now.minus(RETRY_DELAY))
                .flatMap(changed -> changed == 0 ? Mono.empty() : checkout.resume(intent))
                .timeout(ATTEMPT_TIMEOUT)
                .onErrorResume(failure -> {
                    LOG.warn("Payment recovery deferred for intent {}", intent.id());
                    return Mono.empty();
                });
    }
}
