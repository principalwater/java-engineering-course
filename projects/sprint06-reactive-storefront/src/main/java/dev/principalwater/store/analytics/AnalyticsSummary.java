package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record AnalyticsSummary(@JsonSerialize(using = ToStringSerializer.class) long orders, @JsonSerialize(using = ToStringSerializer.class) long units, @JsonSerialize(using = ToStringSerializer.class) long orderValue) {
    @JsonProperty
    @JsonSerialize(using = ToStringSerializer.class)
    public BigDecimal averageOrder() {
        return orders == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(orderValue).divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
    }
}
