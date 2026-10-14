package dev.principalwater.store.dao;

import dev.principalwater.store.model.ShoppingCart;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface CartRepository extends ReactiveCrudRepository<ShoppingCart, UUID> {
    // Корень корзины блокируется без JOIN; тот же SQL поддерживают H2 и PostgreSQL.
    @Query("SELECT id, version FROM shopping_carts WHERE id = :id FOR UPDATE")
    Mono<ShoppingCart> lockById(UUID id);
}
