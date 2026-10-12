package dev.principalwater.store.dao;

import dev.principalwater.store.model.CartLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartLineRepository extends JpaRepository<CartLine, Long> {
    @EntityGraph(attributePaths = "product")
    List<CartLine> findByCartIdOrderById(UUID cartId);

    Optional<CartLine> findByCartIdAndProductId(UUID cartId, long productId);

    long countByCartId(UUID cartId);
}
