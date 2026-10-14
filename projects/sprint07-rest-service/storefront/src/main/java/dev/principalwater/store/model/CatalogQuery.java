package dev.principalwater.store.model;

public record CatalogQuery(String search, SortMode sort, int pageNumber, int pageSize) {
    public static final String DEFAULT_SEARCH = "";
    public static final String DEFAULT_SORT = "NO";
    public static final int FIRST_PAGE = 1;
    public static final int PAGE_SIZE = 5;
    public static final String DEFAULT_PAGE_NUMBER = "" + FIRST_PAGE;
    public static final String DEFAULT_PAGE_SIZE = "" + PAGE_SIZE;

    public CatalogQuery {
        search = search == null ? DEFAULT_SEARCH : search.strip();
        sort = sort == null ? SortMode.NO : sort;
        if (search.length() > StoreLimits.SEARCH_LENGTH) {
            throw new StoreException(StoreError.SEARCH_TOO_LONG);
        }
        if (pageNumber < FIRST_PAGE || !StoreLimits.PAGE_SIZES.contains(pageSize)) {
            throw new StoreException(StoreError.INVALID_PAGING);
        }
    }
}
