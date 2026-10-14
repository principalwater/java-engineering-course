package dev.principalwater.store.model;

public record CartItem(Product product, int quantity) {
    public ItemView view() {
        return ItemView.from(product, quantity);
    }
}
