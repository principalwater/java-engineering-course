package dev.principalwater.store.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("products")
public record Product(@Id Long id, @Version Long version, String sku, String title,
                      String description, ImageAsset image, long price) {
    public Product(ProductInput input) {
        this(null, null, input.sku(), input.title(), input.description(), input.image(), input.price());
    }

    public Product update(ProductInput input) {
        return new Product(id, version, input.sku(), input.title(), input.description(), input.image(), input.price());
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public ImageAsset getImage() { return image; }
    public long getPrice() { return price; }
}
