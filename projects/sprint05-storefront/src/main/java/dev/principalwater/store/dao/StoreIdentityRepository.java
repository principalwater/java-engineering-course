package dev.principalwater.store.dao;

import dev.principalwater.store.model.StoreIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreIdentityRepository extends JpaRepository<StoreIdentity, Integer> {
}
