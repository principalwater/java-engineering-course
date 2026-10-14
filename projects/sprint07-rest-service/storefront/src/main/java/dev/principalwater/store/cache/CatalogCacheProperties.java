package dev.principalwater.store.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("store.cache")
public record CatalogCacheProperties(@DefaultValue("true") boolean enabled,
                                     @DefaultValue("storefront") String namespace,
                                     @DefaultValue("60s") Duration ttl,
                                     @DefaultValue("150ms") Duration operationTimeout,
                                     @DefaultValue("" + CatalogCacheProperties.MAX_ENTRY_BYTES) int maxEntryBytes,
                                     @DefaultValue("2048") int productSlots,
                                     @DefaultValue("128") int pageSlots) {
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SLOTS = 16384;
    private static final Duration MIN_TTL = Duration.ofMillis(1);
    private static final Duration MAX_TTL = Duration.ofMinutes(10);
    private static final Duration MAX_OPERATION_TIMEOUT = Duration.ofSeconds(1);

    public CatalogCacheProperties {
        if (namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Cache namespace must contain 1 to 64 letters, digits, underscores or hyphens");
        }
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Cache TTL must be between " + MIN_TTL + " and " + MAX_TTL);
        }
        if (operationTimeout == null || operationTimeout.isZero() || operationTimeout.isNegative()
                || operationTimeout.compareTo(MAX_OPERATION_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Cache operation timeout must be positive and at most " + MAX_OPERATION_TIMEOUT);
        }
        if (maxEntryBytes <= 0 || maxEntryBytes > MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException("Cache entry limit must be between 1 and " + MAX_ENTRY_BYTES + " bytes");
        }
        if (productSlots <= 0 || productSlots > MAX_SLOTS || pageSlots <= 0 || pageSlots > MAX_SLOTS) {
            throw new IllegalArgumentException("Cache slot count must be between 1 and " + MAX_SLOTS);
        }
    }
}
