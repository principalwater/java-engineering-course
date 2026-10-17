package dev.principalwater.store.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

public record OrderCursor(UUID cartId, Instant createdAt, long id) {
    private static final int MAX_ENCODED_LENGTH = 128;
    private static final int MAX_DATABASE_YEAR = 9999;

    public OrderCursor {
        if (cartId == null || createdAt == null || createdAt.isBefore(Instant.EPOCH)
                || createdAt.atOffset(ZoneOffset.UTC).getYear() > MAX_DATABASE_YEAR || id <= 0) {
            throw new StoreException(StoreError.INVALID_PAGING);
        }
    }

    public String encode() {
        // Колонка TIMESTAMP(3): миллисекунды сохраняют точность границы SQL без округления курсора.
        String value = cartId + ":" + createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    public static OrderCursor parse(String value, UUID ownerCartId) {
        if (value == null || value.isEmpty()) { return null; }
        if (value.length() > MAX_ENCODED_LENGTH) { throw new StoreException(StoreError.INVALID_PAGING); }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 3) { throw new StoreException(StoreError.INVALID_PAGING); }
            UUID cart = UUID.fromString(parts[0]);
            if (!cart.equals(ownerCartId)) { throw new StoreException(StoreError.INVALID_PAGING); }
            return new OrderCursor(cart, Instant.ofEpochMilli(Long.parseLong(parts[1])), Long.parseLong(parts[2]));
        } catch (IllegalArgumentException failure) {
            throw new StoreException(StoreError.INVALID_PAGING);
        }
    }
}
