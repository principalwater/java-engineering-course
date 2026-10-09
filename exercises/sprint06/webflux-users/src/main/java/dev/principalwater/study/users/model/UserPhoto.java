package dev.principalwater.study.users.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_photos")
public record UserPhoto(@Id Long userId, String contentType, byte[] data,
                        @Transient boolean newAggregate) implements Persistable<Long> {
    @PersistenceCreator
    public UserPhoto(Long userId, String contentType, byte[] data) {
        this(userId, contentType, data, false);
    }

    @Override public Long getId() { return userId; }
    @Override public boolean isNew() { return newAggregate; }
}
