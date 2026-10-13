package dev.principalwater.store.model;

public record CatalogQuery(String search, SortMode sort, int pageNumber, int pageSize) {
    public CatalogQuery {
        search = search == null ? "" : search.strip();
        sort = sort == null ? SortMode.NO : sort;
        if (search.length() > StoreLimits.SEARCH_LENGTH) {
            throw new StoreException(StoreError.SEARCH_TOO_LONG);
        }
        if (pageNumber < 1 || !StoreLimits.PAGE_SIZES.contains(pageSize)) {
            throw new StoreException(StoreError.INVALID_PAGING);
        }
    }
}
