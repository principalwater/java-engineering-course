package dev.principalwater.store.dao;

import dev.principalwater.store.model.PurchaseOrder;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderRepository extends ReactiveCrudRepository<PurchaseOrder, Long> {
    Flux<PurchaseOrder> findByCartIdOrderByCreatedAtDescIdDesc(UUID cartId);
    Mono<PurchaseOrder> findByIdAndCartId(long id, UUID cartId);
    Flux<PurchaseOrder> findByIdIn(Collection<Long> ids);
}
