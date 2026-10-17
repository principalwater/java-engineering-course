package dev.principalwater.study.kafka.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Order(UUID id, String username, Instant createdAt) {
    private static final int MAX_USERNAME_LENGTH = 64;
    public Order {
        Objects.requireNonNull(id, "Order ID is required");
        Objects.requireNonNull(createdAt, "Order time is required");
        if (username == null || username.isBlank() || username.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Invalid order username");
        }
    }
}
