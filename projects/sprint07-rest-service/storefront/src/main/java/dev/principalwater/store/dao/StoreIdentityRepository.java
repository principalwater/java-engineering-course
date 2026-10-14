package dev.principalwater.store.dao;

import dev.principalwater.store.model.StoreIdentity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface StoreIdentityRepository extends ReactiveCrudRepository<StoreIdentity, Integer> {
    @Modifying
    @Query("UPDATE store_identity SET catalog_revision = catalog_revision + 1 WHERE id = 1")
    Mono<Long> advanceCatalogRevision();
}
