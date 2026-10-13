package dev.principalwater.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "cart_items")
public class CartLine {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cart_line_ids")
    @SequenceGenerator(name = "cart_line_ids", sequenceName = "cart_line_seq", allocationSize = StoreLimits.JPA_BATCH_SIZE)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private ShoppingCart cart;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    @Column(nullable = false)
    private int quantity;

    protected CartLine() {
    }

    public CartLine(ShoppingCart cart, Product product) {
        this.cart = cart;
        this.product = product;
        quantity = 1;
    }

    public void increment() {
        if (quantity >= StoreLimits.MAX_ITEM_QUANTITY) {
            throw new StoreException(StoreError.QUANTITY_LIMIT);
        }
        quantity++;
    }

    public void decrement() {
        if (quantity <= 1) {
            throw new IllegalStateException("Remove the cart line when its quantity reaches zero");
        }
        quantity--;
    }

    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
}
