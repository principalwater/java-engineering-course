package dev.principalwater.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_items")
public class OrderLine {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_line_ids")
    @SequenceGenerator(name = "order_line_ids", sequenceName = "order_line_seq", allocationSize = StoreLimits.JPA_BATCH_SIZE)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private PurchaseOrder order;
    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;
    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;
    @Column(nullable = false, updatable = false, length = StoreLimits.SKU_LENGTH)
    private String sku;
    @Column(nullable = false, updatable = false, length = StoreLimits.TITLE_LENGTH)
    private String title;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = StoreLimits.IMAGE_NAME_LENGTH)
    private ImageAsset image;
    @Column(nullable = false, updatable = false)
    private long price;
    @Column(nullable = false, updatable = false)
    private int quantity;

    protected OrderLine() {
    }

    public OrderLine(PurchaseOrder order, int lineNumber, CartLine item) {
        // Снимок не зависит от будущих изменений цены, названия или удаления товара из каталога.
        this.order = order;
        this.lineNumber = lineNumber;
        var product = item.getProduct();
        productId = product.getId();
        sku = product.getSku();
        title = product.getTitle();
        image = product.getImage();
        price = product.getPrice();
        quantity = item.getQuantity();
    }

    public long total() { return Math.multiplyExact(price, quantity); }
    public int getLineNumber() { return lineNumber; }
    public long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public ImageAsset getImage() { return image; }
    public long getPrice() { return price; }
    public int getQuantity() { return quantity; }
}
