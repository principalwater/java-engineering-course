package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartLineRepository;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.CartLine;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.ItemView;
import dev.principalwater.store.model.ShoppingCart;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreLimits;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CartService {
    private final CartRepository carts;
    private final CartLineRepository lines;
    private final ProductRepository products;

    public CartService(CartRepository carts, CartLineRepository lines, ProductRepository products) {
        this.carts = carts;
        this.lines = lines;
        this.products = products;
    }

    @Transactional
    public UUID identify(UUID cookieId) {
        if (cookieId != null && carts.existsById(cookieId)) {
            return cookieId;
        }
        return carts.save(new ShoppingCart(UUID.randomUUID())).getId();
    }

    public CartView view(UUID cartId) {
        return new CartView(lines.findByCartIdOrderById(cartId).stream()
                .map(line -> ItemView.from(line.getProduct(), line.getQuantity())).toList());
    }

    @Transactional
    public void change(UUID cartId, long productId, CartAction action) {
        var cart = carts.lockById(cartId).orElseThrow(() -> new StoreException(StoreError.CART_NOT_FOUND));
        var product = products.findById(productId).orElseThrow(() -> new StoreException(StoreError.PRODUCT_NOT_FOUND));
        var existing = lines.findByCartIdAndProductId(cartId, productId);
        switch (action) {
            case PLUS -> {
                if (existing.isPresent()) {
                    existing.get().increment();
                } else {
                    if (lines.countByCartId(cartId) >= StoreLimits.MAX_CART_ITEMS) {
                        throw new StoreException(StoreError.CART_LIMIT);
                    }
                    lines.save(new CartLine(cart, product));
                }
            }
            case MINUS -> existing.ifPresent(line -> {
                if (line.getQuantity() == 1) {
                    lines.delete(line);
                } else {
                    line.decrement();
                }
            });
            case DELETE -> existing.ifPresent(lines::delete);
        }
    }
}
