package dev.principalwater.store.cache;

import dev.principalwater.store.model.Product;
import java.util.List;

public record CatalogSnapshot(List<Product> products, long totalItems) {
    public CatalogSnapshot {
        products = List.copyOf(products);
        if (totalItems < products.size()) {
            throw new IllegalArgumentException("Catalog total cannot be smaller than its page");
        }
    }
}
