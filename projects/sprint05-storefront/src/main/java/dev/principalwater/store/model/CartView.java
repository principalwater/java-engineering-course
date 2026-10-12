package dev.principalwater.store.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record CartView(List<ItemView> items) {
    public CartView {
        items = List.copyOf(items);
    }

    public long total() {
        return items.stream().mapToLong(ItemView::total).reduce(0, Math::addExact);
    }

    public int itemCount() {
        return items.stream().mapToInt(ItemView::count).sum();
    }

    public Map<Long, Integer> counts() {
        return items.stream().collect(Collectors.toUnmodifiableMap(ItemView::id, ItemView::count));
    }
}
