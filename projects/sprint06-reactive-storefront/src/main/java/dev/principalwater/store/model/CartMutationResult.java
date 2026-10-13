package dev.principalwater.store.model;

public record CartMutationResult(long itemId, int count, CartView cart) {
    public static CartMutationResult from(long id, CartView cart) {
        return new CartMutationResult(id, cart.counts().getOrDefault(id, 0), cart);
    }
}
