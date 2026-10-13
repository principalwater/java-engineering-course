package dev.principalwater.store.dao;

import dev.principalwater.store.model.ShoppingCart;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<ShoppingCart, UUID> {
    // Блокируется только корень корзины: FOR UPDATE с внешним JOIN непереносим между H2 и PostgreSQL.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cart from ShoppingCart cart where cart.id = :id")
    Optional<ShoppingCart> lockById(@Param("id") UUID id);
}
