package dev.principalwater.store.dao;

import dev.principalwater.store.model.Product;
import java.util.Collection;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {
    Flux<Product> findAllBySkuIn(Collection<String> skus);
}
