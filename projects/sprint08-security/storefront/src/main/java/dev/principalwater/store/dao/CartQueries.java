package dev.principalwater.store.dao;

import dev.principalwater.store.model.CartItem;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.payment.CheckoutSnapshot;
import io.r2dbc.spi.Row;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class CartQueries {
    // Одна SQL-выборка связывает цены с обеими revision без глобальной блокировки каталога.
    private static final String SNAPSHOT_SQL = """
            SELECT s.version AS cart_version, i.catalog_revision,
                   p.id, p.version, p.sku, p.title, p.description, p.image, p.price, c.quantity
            FROM shopping_carts s CROSS JOIN store_identity i
            LEFT JOIN cart_items c ON c.cart_id = s.id LEFT JOIN products p ON p.id = c.product_id
            WHERE s.id = :cartId AND i.id = 1 ORDER BY c.id
            """;
    // Ключ снимка хеширует cartId (две long), версию корзины и revision каталога.
    private static final int SNAPSHOT_KEY_BYTES = 4 * Long.BYTES;
    private final DatabaseClient database;
    public CartQueries(DatabaseClient database) {
        this.database = database;
    }
    public Flux<CartItem> items(UUID cartId) { return snapshot(cartId).flatMapMany(view -> Flux.fromIterable(view.items())); }
    public Mono<CheckoutSnapshot> snapshot(UUID cartId) {
        return database.sql(SNAPSHOT_SQL).bind("cartId", cartId).map((row, metadata) -> snapshotRow(row)).all().collectList()
                .map(rows -> checkoutSnapshot(cartId, rows));
    }
    // LEFT JOIN: пустая корзина даёт одну строку без товара.
    private static SnapshotRow snapshotRow(Row row) {
        return new SnapshotRow(row.get("cart_version", Long.class), row.get("catalog_revision", Long.class),
                row.get("id", Long.class) == null ? null : item(row));
    }
    private static CheckoutSnapshot checkoutSnapshot(UUID cartId, List<SnapshotRow> rows) {
        if (rows.isEmpty()) { throw new StoreException(StoreError.CART_NOT_FOUND); }
        var first = rows.getFirst();
        return new CheckoutSnapshot(snapshotKey(cartId, first.cartVersion(), first.catalogRevision()),
                rows.stream().map(SnapshotRow::item).filter(Objects::nonNull).toList());
    }
    // Это идентификатор снимка, не секрет и не право доступа: владельца проверяет сессия.
    private static UUID snapshotKey(UUID cartId, long cartVersion, long catalogRevision) {
        return UUID.nameUUIDFromBytes(ByteBuffer.allocate(SNAPSHOT_KEY_BYTES)
                .putLong(cartId.getMostSignificantBits()).putLong(cartId.getLeastSignificantBits())
                .putLong(cartVersion).putLong(catalogRevision).array());
    }
    private static CartItem item(Row row) {
        return new CartItem(new Product(row.get("id", Long.class), row.get("version", Long.class),
                row.get("sku", String.class), row.get("title", String.class), row.get("description", String.class),
                ImageAsset.valueOf(row.get("image", String.class)), row.get("price", Long.class)), row.get("quantity", Integer.class));
    }
    private record SnapshotRow(long cartVersion, long catalogRevision, CartItem item) { }
}
