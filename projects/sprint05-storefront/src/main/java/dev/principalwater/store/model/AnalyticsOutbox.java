package dev.principalwater.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "analytics_outbox")
public class AnalyticsOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_ids")
    @SequenceGenerator(name = "outbox_ids", sequenceName = "outbox_seq", allocationSize = StoreLimits.JPA_BATCH_SIZE)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private PurchaseOrder order;
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected AnalyticsOutbox() {
    }

    public AnalyticsOutbox(PurchaseOrder order) {
        this.order = order;
    }
}
