package dev.principalwater.store.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record AnalyticsSummary(long orders, long units, long orderValue) {
    public BigDecimal averageOrder() {
        return orders == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(orderValue).divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
    }
}
