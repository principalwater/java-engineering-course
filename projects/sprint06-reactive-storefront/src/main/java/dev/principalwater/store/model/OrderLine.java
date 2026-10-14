package dev.principalwater.store.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("order_items")
public record OrderLine(@Id Long id, long orderId, int lineNumber, long productId, String sku,
                        String title, ImageAsset image, long price, int quantity) {
    public OrderLine(long orderId, int lineNumber, CartItem item) {
        // Исторический снимок не зависит от последующих изменений каталога.
        this(null, orderId, lineNumber, item.product().id(), item.product().sku(), item.product().title(),
                item.product().image(), item.product().price(), item.quantity());
    }

    public long total() { return Math.multiplyExact(price, quantity); }
    public int getLineNumber() { return lineNumber; }
    public long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public ImageAsset getImage() { return image; }
    public long getPrice() { return price; }
    public int getQuantity() { return quantity; }
}
