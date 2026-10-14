package dev.principalwater.store.dao;

import dev.principalwater.store.model.StoreIdentity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface StoreIdentityRepository extends ReactiveCrudRepository<StoreIdentity, Integer> {
}
