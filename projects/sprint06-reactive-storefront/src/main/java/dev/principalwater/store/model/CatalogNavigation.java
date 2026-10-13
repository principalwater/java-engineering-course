package dev.principalwater.store.model;

public record CatalogNavigation(String search, SortMode sort, Integer pageNumber, Integer pageSize) {
    public CatalogQuery query() {
        return new CatalogQuery(search, sort, pageNumber == null ? 1 : pageNumber, pageSize == null ? 5 : pageSize);
    }
}
