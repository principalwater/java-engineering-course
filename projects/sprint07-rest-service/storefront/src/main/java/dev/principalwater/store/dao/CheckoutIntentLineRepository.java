package dev.principalwater.store.dao;

import dev.principalwater.store.payment.CheckoutIntentLine;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface CheckoutIntentLineRepository extends ReactiveCrudRepository<CheckoutIntentLine, Long> {
    Flux<CheckoutIntentLine> findByIntentIdOrderByLineNumber(UUID intentId);
}
