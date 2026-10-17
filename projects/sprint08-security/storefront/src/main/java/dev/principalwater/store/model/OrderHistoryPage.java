package dev.principalwater.store.model;

import java.util.List;

public record OrderHistoryPage(List<OrderView> orders, String nextCursor) {
    public static final String DEFAULT_PAGE_SIZE = "20";

    public OrderHistoryPage {
        orders = List.copyOf(orders);
    }
}
