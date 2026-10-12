package dev.principalwater.store.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_ids")
    @SequenceGenerator(name = "order_ids", sequenceName = "order_seq", allocationSize = StoreLimits.JPA_BATCH_SIZE)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private ShoppingCart cart;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "total_sum", nullable = false, updatable = false)
    private long totalSum;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(ShoppingCart cart, List<CartLine> items, Instant createdAt) {
        if (items.isEmpty()) {
            throw new StoreException(StoreError.EMPTY_CART);
        }
        this.cart = cart;
        this.createdAt = createdAt;
        eventId = UUID.randomUUID();
        for (int index = 0; index < items.size(); index++) {
            var line = new OrderLine(this, index, items.get(index));
            lines.add(line);
            totalSum = Math.addExact(totalSum, line.total());
        }
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public Instant getCreatedAt() { return createdAt; }
    public long getTotalSum() { return totalSum; }
    public List<OrderLine> getLines() { return List.copyOf(lines); }
}
