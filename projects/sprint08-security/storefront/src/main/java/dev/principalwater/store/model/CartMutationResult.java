package dev.principalwater.store.model;

import java.util.UUID;

public record CartMutationResult(long itemId, int count, CartView cart, String checkoutKey) {
    public static CartMutationResult from(long id, CartView cart, UUID checkoutKey) {
        return new CartMutationResult(id, cart.counts().getOrDefault(id, 0), cart, checkoutKey.toString());
    }
}
