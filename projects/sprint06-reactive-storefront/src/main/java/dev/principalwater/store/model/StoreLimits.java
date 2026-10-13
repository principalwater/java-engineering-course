package dev.principalwater.store.model;

import java.util.List;

public final class StoreLimits {
    public static final int TITLE_LENGTH = 120;
    public static final int DESCRIPTION_LENGTH = 5000;
    public static final int SKU_LENGTH = 64;
    public static final int IMAGE_NAME_LENGTH = 32;
    public static final int SEARCH_LENGTH = 200;
    public static final long MAX_PRICE_RUBLES = 1_000_000;
    public static final int MAX_ITEM_QUANTITY = 99;
    public static final int MAX_CART_ITEMS = 50;
    public static final int MAX_IMPORT_ITEMS = 1000;
    public static final int MAX_IMPORT_BYTES = 1024 * 1024;
    public static final int JPA_BATCH_SIZE = 50;
    public static final List<Integer> PAGE_SIZES = List.of(2, 5, 10, 20, 50, 100);

    private StoreLimits() {
    }
}
