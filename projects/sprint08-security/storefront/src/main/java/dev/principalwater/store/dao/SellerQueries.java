package dev.principalwater.store.dao;

import dev.principalwater.store.model.SellerView;
import dev.principalwater.store.util.ExactNumbers;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class SellerQueries {
    private final DatabaseClient database;

    public SellerQueries(DatabaseClient database) {
        this.database = database;
    }

    public Mono<SellerView> summary() {
        return database.sql("""
                SELECT COUNT(*) AS paid_orders, COALESCE(SUM(o.total_sum), 0) AS paid_value,
                       COALESCE(SUM((SELECT SUM(l.quantity) FROM order_items l WHERE l.order_id = o.id)), 0) AS units,
                       MAX(o.created_at) AS last_order,
                       (SELECT COUNT(*) FROM checkout_intents i WHERE i.status = 'PREPARED') AS pending_payments
                FROM purchase_orders o WHERE o.status = 'PAID'
                """).map(row -> {
                    var last = row.get("last_order", OffsetDateTime.class);
                    return new SellerView(ExactNumbers.longValueExact((Number) row.get("paid_orders")),
                            ExactNumbers.longValueExact((Number) row.get("paid_value")),
                            ExactNumbers.longValueExact((Number) row.get("units")),
                            last == null ? null : last.toInstant(),
                            ExactNumbers.longValueExact((Number) row.get("pending_payments")));
                }).one();
    }
}
