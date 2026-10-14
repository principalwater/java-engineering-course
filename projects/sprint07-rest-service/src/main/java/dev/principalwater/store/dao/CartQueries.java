package dev.principalwater.store.dao;

import dev.principalwater.store.model.CartItem;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.Product;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public class CartQueries {
    private final DatabaseClient database;

    public CartQueries(DatabaseClient database) {
        this.database = database;
    }

    public Flux<CartItem> items(UUID cartId) {
        return database.sql("""
                SELECT p.id, p.version, p.sku, p.title, p.description, p.image, p.price, c.quantity
                FROM cart_items c JOIN products p ON p.id = c.product_id
                WHERE c.cart_id = :cartId ORDER BY c.id
                """).bind("cartId", cartId).map((row, metadata) -> new CartItem(
                        new Product(row.get("id", Long.class), row.get("version", Long.class),
                                row.get("sku", String.class), row.get("title", String.class),
                                row.get("description", String.class), ImageAsset.valueOf(row.get("image", String.class)),
                                row.get("price", Long.class)), row.get("quantity", Integer.class))).all();
    }
}
