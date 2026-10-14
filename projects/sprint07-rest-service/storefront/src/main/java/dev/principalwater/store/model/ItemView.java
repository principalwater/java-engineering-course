package dev.principalwater.store.model;

public record ItemView(long id, String sku, String title, String description, String imgPath, long price, int count) {
    public long total() {
        return Math.multiplyExact(price, count);
    }

    public static ItemView from(Product product, int count) {
        return new ItemView(product.getId(), product.getSku(), product.getTitle(), product.getDescription(),
                product.getImage().getPath(), product.getPrice(), count);
    }
}
