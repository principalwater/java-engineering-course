package dev.principalwater.store.model;

import java.time.Instant;

public record ProfileView(long totalOrders, long orderValue, long units, Instant lastOrder,
                          long paidValue, long paidOrders, long pendingPayments) {
}
