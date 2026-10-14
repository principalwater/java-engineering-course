package dev.principalwater.store.dao;

import dev.principalwater.store.model.CartLine;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CartLineRepository extends ReactiveCrudRepository<CartLine, Long> {
    Flux<CartLine> findByCartIdOrderById(UUID cartId);
    Mono<CartLine> findByCartIdAndProductId(UUID cartId, long productId);
    Mono<Long> countByCartId(UUID cartId);
    Mono<Void> deleteByCartId(UUID cartId);
}
