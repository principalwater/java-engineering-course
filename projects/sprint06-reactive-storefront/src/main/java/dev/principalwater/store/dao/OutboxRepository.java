package dev.principalwater.store.dao;

import dev.principalwater.store.model.AnalyticsOutbox;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<AnalyticsOutbox, Long> {
    @Query("select entry.order.id from AnalyticsOutbox entry where entry.deliveredAt is null order by entry.id")
    List<Long> pendingOrderIds(Pageable pageable);

    long countByDeliveredAtIsNull();

    @Query("select max(entry.deliveredAt) from AnalyticsOutbox entry")
    Optional<Instant> latestDelivery();

    @Modifying
    @Query("update AnalyticsOutbox entry set entry.deliveredAt = :time where entry.order.id in :ids and entry.deliveredAt is null")
    int acknowledge(@Param("ids") Collection<Long> ids, @Param("time") Instant time);
}
