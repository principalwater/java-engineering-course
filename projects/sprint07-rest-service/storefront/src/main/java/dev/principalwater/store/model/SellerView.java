package dev.principalwater.store.model;

import java.time.Instant;

public record SellerView(long paidOrders, long paidValue, long units, Instant lastOrder, long pendingPayments) {
}
