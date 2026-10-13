package dev.principalwater.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Version;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_ids")
    @SequenceGenerator(name = "product_ids", sequenceName = "product_seq", allocationSize = StoreLimits.JPA_BATCH_SIZE)
    private Long id;
    @Version
    private Long version;
    @Column(nullable = false, unique = true, length = StoreLimits.SKU_LENGTH)
    private String sku;
    @Column(nullable = false, length = StoreLimits.TITLE_LENGTH)
    private String title;
    @Column(nullable = false, length = StoreLimits.DESCRIPTION_LENGTH)
    private String description;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = StoreLimits.IMAGE_NAME_LENGTH)
    private ImageAsset image;
    // Контракт витрины задаёт long price: цена хранится в целых рублях, без float/double.
    @Column(nullable = false)
    private long price;

    protected Product() {
    }

    public Product(ProductInput input) {
        update(input);
    }

    public void update(ProductInput input) {
        sku = input.sku();
        title = input.title();
        description = input.description();
        image = input.image();
        price = input.price();
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public ImageAsset getImage() { return image; }
    public long getPrice() { return price; }
}
