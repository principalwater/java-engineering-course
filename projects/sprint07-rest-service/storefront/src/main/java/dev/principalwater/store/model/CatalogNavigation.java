package dev.principalwater.store.model;

public record CatalogNavigation(String search, SortMode sort, Integer pageNumber, Integer pageSize) {
    public CatalogQuery query() {
        return new CatalogQuery(search, sort, pageNumber == null ? CatalogQuery.FIRST_PAGE : pageNumber, pageSize == null ? CatalogQuery.PAGE_SIZE : pageSize);
    }
}
