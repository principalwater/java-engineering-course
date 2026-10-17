package dev.principalwater.store.model;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("store_identity")
public record StoreIdentity(@Id Integer id, UUID sourceId, long catalogRevision) {
    public static final int SINGLETON_ID = 1;

    public UUID getSourceId() { return sourceId; }
}
