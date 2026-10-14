package dev.principalwater.store.model;

public record OrderLineView(long id, String sku, String title, String imgPath, long price, int count) {
    public long total() {
        return Math.multiplyExact(price, count);
    }

    public static OrderLineView from(OrderLine line) {
        return new OrderLineView(line.getProductId(), line.getSku(), line.getTitle(),
                line.getImage().getPath(), line.getPrice(), line.getQuantity());
    }
}
