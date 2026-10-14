package dev.principalwater.store;

import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.ProductInput;

public final class ProductFixtures {
    private ProductFixtures() {
    }

    public static ProductInput productInput(String sku, String title, String description, long price) {
        return new ProductInput(sku, title, description, ImageAsset.KEYBOARD, price);
    }
}
