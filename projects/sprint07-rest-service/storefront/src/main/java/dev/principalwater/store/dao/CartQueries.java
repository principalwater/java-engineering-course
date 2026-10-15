package dev.principalwater.store.dao;

import dev.principalwater.store.model.CartItem;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.payment.CheckoutSnapshot;
import io.r2dbc.spi.Row;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class CartQueries {
    private final DatabaseClient database;
    public CartQueries(DatabaseClient database) {
        this.database = database;
    }
    public Flux<CartItem> items(UUID cartId) { return snapshot(cartId).flatMapMany(view -> Flux.fromIterable(view.items())); }
    public Mono<CheckoutSnapshot> snapshot(UUID cartId) {
        // Одна SQL-выборка связывает цены с обеими revision без глобальной блокировки каталога.
        return database.sql("""
                SELECT s.version AS cart_version, i.catalog_revision,
                       p.id, p.version, p.sku, p.title, p.description, p.image, p.price, c.quantity
                FROM shopping_carts s CROSS JOIN store_identity i
                LEFT JOIN cart_items c ON c.cart_id = s.id LEFT JOIN products p ON p.id = c.product_id
                WHERE s.id = :cartId AND i.id = 1 ORDER BY c.id
                """).bind("cartId", cartId).map((row, metadata) -> new SnapshotRow(
                        row.get("cart_version", Long.class), row.get("catalog_revision", Long.class),
                        row.get("id", Long.class) == null ? null : item(row))).all().collectList().map(rows -> {
                    if (rows.isEmpty()) { throw new StoreException(StoreError.CART_NOT_FOUND); }
                    var first = rows.getFirst();
                    // Это идентификатор снимка, не секрет и не право доступа: владельца проверяет сессия.
                    UUID key = UUID.nameUUIDFromBytes(ByteBuffer.allocate(4 * Long.BYTES)
                            .putLong(cartId.getMostSignificantBits()).putLong(cartId.getLeastSignificantBits())
                            .putLong(first.cartVersion()).putLong(first.catalogRevision()).array());
                    return new CheckoutSnapshot(key, rows.stream().map(SnapshotRow::item).filter(java.util.Objects::nonNull).toList());
                });
    }
    private CartItem item(Row row) {
        return new CartItem(new Product(row.get("id", Long.class), row.get("version", Long.class),
                row.get("sku", String.class), row.get("title", String.class), row.get("description", String.class),
                ImageAsset.valueOf(row.get("image", String.class)), row.get("price", Long.class)), row.get("quantity", Integer.class));
    }
    private record SnapshotRow(long cartVersion, long catalogRevision, CartItem item) { }
}
