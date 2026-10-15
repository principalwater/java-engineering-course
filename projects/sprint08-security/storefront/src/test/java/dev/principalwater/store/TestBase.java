package dev.principalwater.store;

import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@Timeout(30)
public abstract class TestBase {
    protected static void assertStoreError(StoreError expected, Throwable failure) {
        assertEquals(expected, assertInstanceOf(StoreException.class, failure).getError());
    }
}
