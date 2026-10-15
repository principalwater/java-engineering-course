package dev.principalwater.store.dao;

import dev.principalwater.store.model.StoreUser;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface StoreUserRepository extends ReactiveCrudRepository<StoreUser, UUID> {
    Mono<StoreUser> findByUsername(String username);
    Mono<StoreUser> findByCartId(UUID cartId);
}
