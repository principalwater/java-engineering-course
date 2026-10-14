package dev.principalwater.store.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class VisitorIdentity {
    private VisitorIdentity() {
    }

    public static UUID analyticsId(UUID cartId) {
        // Псевдоним гостя сохраняет ключ факта, но не передаёт capability-cookie корзины в аналитическую БД.
        return UUID.nameUUIDFromBytes(("storefront-visitor:" + cartId).getBytes(StandardCharsets.UTF_8));
    }
}
