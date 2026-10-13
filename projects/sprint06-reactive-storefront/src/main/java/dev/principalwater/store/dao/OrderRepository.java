package dev.principalwater.store.dao;

import dev.principalwater.store.model.PurchaseOrder;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<PurchaseOrder, Long> {
    @EntityGraph(attributePaths = "lines")
    List<PurchaseOrder> findByCartIdOrderByCreatedAtDescIdDesc(UUID cartId);

    @EntityGraph(attributePaths = "lines")
    Optional<PurchaseOrder> findByIdAndCartId(long id, UUID cartId);

    @EntityGraph(attributePaths = "lines")
    List<PurchaseOrder> findByIdIn(Collection<Long> ids);
}
