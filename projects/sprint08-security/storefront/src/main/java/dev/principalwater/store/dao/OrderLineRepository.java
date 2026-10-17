package dev.principalwater.store.dao;

import dev.principalwater.store.model.OrderLine;
import java.util.Collection;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface OrderLineRepository extends ReactiveCrudRepository<OrderLine, Long> {
    Flux<OrderLine> findByOrderIdOrderByLineNumber(long orderId);
    Flux<OrderLine> findByOrderIdInOrderByOrderIdAscLineNumberAsc(Collection<Long> ids);
}
