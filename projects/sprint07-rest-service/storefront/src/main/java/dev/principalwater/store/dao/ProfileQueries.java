package dev.principalwater.store.dao;

import dev.principalwater.store.model.ProfileView;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class ProfileQueries {
    private final DatabaseClient database;

    public ProfileQueries(DatabaseClient database) {
        this.database = database;
    }

    public Mono<ProfileView> summary(UUID cartId) {
        return database.sql("""
                SELECT COUNT(*) AS total_orders, COALESCE(SUM(o.total_sum), 0) AS order_value,
                       COALESCE(SUM((SELECT SUM(l.quantity) FROM order_items l WHERE l.order_id = o.id)), 0) AS units,
                       MAX(o.created_at) AS last_order,
                       COALESCE(SUM(CASE WHEN o.status = 'PAID' THEN o.total_sum ELSE 0 END), 0) AS paid_value,
                       COALESCE(SUM(CASE WHEN o.status = 'PAID' THEN 1 ELSE 0 END), 0) AS paid_orders,
                       (SELECT COUNT(*) FROM checkout_intents i
                        WHERE i.cart_id = :cartId AND i.status = 'PREPARED') AS pending_payments
                FROM purchase_orders o WHERE o.cart_id = :cartId
                """).bind("cartId", cartId).map((row, metadata) -> {
                    var last = row.get("last_order", OffsetDateTime.class);
                    return new ProfileView(((Number) row.get("total_orders")).longValue(),
                            ((Number) row.get("order_value")).longValue(), ((Number) row.get("units")).longValue(),
                            last == null ? null : last.toInstant(), ((Number) row.get("paid_value")).longValue(),
                            ((Number) row.get("paid_orders")).longValue(), ((Number) row.get("pending_payments")).longValue());
                }).one();
    }
}
