package dev.principalwater.store.model;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoreModelTest {
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
    @ValueSource(ints = {0, 1, 3, 101})
    void rejectsPageSizesOutsideThePublishedChoices(int size) {
        var error = assertThrows(StoreException.class, () -> new CatalogQuery("", SortMode.NO, 1, size));
        assertEquals(StoreError.INVALID_PAGING, error.getError());
    }
}
