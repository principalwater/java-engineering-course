package dev.principalwater.store.service;

import dev.principalwater.store.cache.CatalogReads;

import dev.principalwater.store.dao.*;

import dev.principalwater.store.model.*;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
public class CartService {
    private final CartRepository carts;
    private final CartLineRepository lines;
    private final CatalogReads catalog;
    private final ProductRepository products;
    private final StoreUserRepository users;
    private final TransactionalOperator transactions;

    public CartService(CartRepository carts, CartLineRepository lines, CatalogReads catalog,
                       ProductRepository products, StoreUserRepository users, TransactionalOperator transactions) {
        this.carts = carts;
        this.lines = lines;
        this.catalog = catalog;
        this.products = products;
        this.users = users;
        this.transactions = transactions;
    }

    public Mono<UUID> identify(UUID cookieId) {
        return Mono.defer(() -> cookieId == null ? create() : users.existsByCartId(cookieId)
                // Cookie даёт доступ только к гостевой корзине; корзина аккаунта требует authentication.
                .flatMap(accountCart -> accountCart ? create() : carts.existsById(cookieId)
                        .flatMap(exists -> exists ? Mono.just(cookieId) : create())));
    }

    private Mono<UUID> create() {
        return carts.save(new ShoppingCart(UUID.randomUUID())).map(ShoppingCart::id);
    }

    public Mono<CartView> view(UUID cartId) {
        return lines.findByCartIdOrderById(cartId).collectList().flatMap(selected ->
                catalog.products(selected.stream().map(CartLine::productId).toList()).map(productsById -> new CartView(
                        selected.stream().map(line -> {
                            Product product = productsById.get(line.productId());
                            if (product == null) {
                                throw new StoreException(StoreError.PRODUCT_NOT_FOUND);
                            }
                            return ItemView.from(product, line.quantity());
                        }).toList())));
    }

    public Mono<CartView> change(UUID cartId, long productId, CartAction action) {
        return change(cartId, productId, action, null);
    }

    public Mono<CartView> change(UUID cartId, long productId, CartAction action, Integer quantity) {
        return Mono.defer(() -> {
            if (productId <= 0 || action == null) {
                return Mono.error(new StoreException(StoreError.INVALID_REQUEST));
            }
            if (action == CartAction.SET && (quantity == null || quantity < 0 || quantity > StoreLimits.MAX_ITEM_QUANTITY)) {
                return Mono.error(new StoreException(StoreError.INVALID_QUANTITY));
            }
            // Redis читается после commit: сетевой кэш не удерживает SQL-блокировку корзины.
            return carts.lockById(cartId).switchIfEmpty(Mono.error(new StoreException(StoreError.CART_NOT_FOUND)))
                    .then(products.findById(productId)
                            .switchIfEmpty(Mono.error(new StoreException(StoreError.PRODUCT_NOT_FOUND))))
                    .then(lines.findByCartIdAndProductId(cartId, productId).map(Optional::of)
                            .defaultIfEmpty(Optional.empty()))
                    .flatMap(existing -> update(cartId, productId, existing.orElse(null), action, quantity))
                    .then().as(transactions::transactional).then(Mono.defer(() -> view(cartId)));
        });
    }

    private Mono<Void> update(UUID cartId, long productId, CartLine line, CartAction action, Integer quantity) {
        int count = line == null ? 0 : line.quantity();
        int target = switch (action) {
            case PLUS -> count + 1;
            case MINUS -> Math.max(0, count - 1);
            case DELETE -> 0;
            case SET -> quantity;
        };
        if (target > StoreLimits.MAX_ITEM_QUANTITY) {
            return Mono.error(new StoreException(StoreError.QUANTITY_LIMIT));
        }
        if (target == 0) {
            return line == null ? Mono.empty() : lines.delete(line);
        }
        if (line != null) {
            return lines.save(line.withQuantity(target)).then();
        }
        return lines.countByCartId(cartId).flatMap(total -> total >= StoreLimits.MAX_CART_ITEMS
                ? Mono.error(new StoreException(StoreError.CART_LIMIT))
                : lines.save(new CartLine(cartId, productId, target)).then());
    }
}
