package dev.principalwater.store.model;

import java.util.List;

public record CatalogPage(List<ItemView> items, Paging paging, long totalItems) {
    public CatalogPage {
        items = List.copyOf(items);
    }
}
