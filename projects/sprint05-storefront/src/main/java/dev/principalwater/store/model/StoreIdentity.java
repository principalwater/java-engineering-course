package dev.principalwater.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "store_identity")
public class StoreIdentity {
    public static final int SINGLETON_ID = 1;
    @Id
    private Integer id;
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    protected StoreIdentity() {
    }

    public UUID getSourceId() { return sourceId; }
}
