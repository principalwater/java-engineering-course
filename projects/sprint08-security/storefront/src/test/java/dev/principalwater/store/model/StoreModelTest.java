package dev.principalwater.store.model;

import dev.principalwater.store.TestBase;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreModelTest extends TestBase {
    @Test
    void moneyTotalsStayLongWhenAnAllowedCartExceedsIntegerRange() {
        var items = new ArrayList<ItemView>();
        for (int id = 1; id <= 50; id++) {
            items.add(new ItemView(id, "item-" + id, "Item", "Description", "/images/cup.svg", 1_000_000, 99));
        }
        var cart = new CartView(items);
        assertEquals(4_950_000_000L, cart.total());
        assertEquals(4950, cart.itemCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@example.test", "first.last+tag@sub.example.test", "UPPER@EXAMPLE.TEST", "a_b%c@x-y.example.test"})
    void acceptsOrdinaryAsciiContactAddresses(String email) {
        assertTrue(AccountRules.validEmail(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "plain", "a@example", "@example.test", "a@.test", "a@example..test", "a b@example.test",
            ".a@example.test", "a.@example.test", "a..b@example.test", "тест@example.test", "a@example.test\n",
            "a@example.test,b@example.test", "<a@example.test>"})
    void rejectsAddressesThatCouldNotReceiveOrThatInjectHeaders(String email) {
        assertFalse(AccountRules.validEmail(email));
    }

    @Test
    void contactAddressesAreComparedWithoutCaseOrOuterWhitespace() {
        assertEquals("alice@example.test", AccountRules.normalizeEmail("  Alice@Example.TEST "));
        assertFalse(AccountRules.validEmail("a".repeat(65) + "@example.test"));
        assertFalse(AccountRules.validEmail("a@" + "b".repeat(64) + ".test"));
        assertFalse(AccountRules.validEmail(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 101})
    void rejectsPageSizesOutsideThePublishedChoices(int size) {
        var error = assertThrows(StoreException.class, () -> new CatalogQuery("", SortMode.NO, 1, size));
        assertStoreError(StoreError.INVALID_PAGING, error);
    }
}
