package dev.principalwater.store.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

public record OrderCursor(Instant createdAt, long id) {
    private static final int MAX_ENCODED_LENGTH = 64;
    private static final int MAX_DATABASE_YEAR = 9999;

    public OrderCursor {
        if (createdAt == null || createdAt.isBefore(Instant.EPOCH)
                || createdAt.atOffset(ZoneOffset.UTC).getYear() > MAX_DATABASE_YEAR || id <= 0) {
            throw new StoreException(StoreError.INVALID_PAGING);
        }
    }

    public String encode() {
        // Колонка TIMESTAMP(3): миллисекунды сохраняют точность границы SQL без округления курсора.
        String value = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    public static OrderCursor parse(String value) {
        if (value == null || value.isEmpty()) { return null; }
        if (value.length() > MAX_ENCODED_LENGTH) { throw new StoreException(StoreError.INVALID_PAGING); }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 2) { throw new StoreException(StoreError.INVALID_PAGING); }
            return new OrderCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException failure) {
            throw new StoreException(StoreError.INVALID_PAGING);
        }
    }
}
