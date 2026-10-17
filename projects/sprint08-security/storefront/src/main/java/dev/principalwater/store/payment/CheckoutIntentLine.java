package dev.principalwater.store.payment;

import dev.principalwater.store.model.CartItem;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.OrderLine;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("checkout_intent_lines")
public record CheckoutIntentLine(@Id Long id, UUID intentId, int lineNumber, long productId, String sku,
                                 String title, ImageAsset image, long price, int quantity) {
    public CheckoutIntentLine(UUID intentId, int number, CartItem item) {
        this(null, intentId, number, item.product().id(), item.product().sku(), item.product().title(),
                item.product().image(), item.product().price(), item.quantity());
    }
    public OrderLine orderLine(long orderId) {
        return new OrderLine(null, orderId, lineNumber, productId, sku, title, image, price, quantity);
    }
}
