package dev.principalwater.store.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record AnalyticsSummary(@JsonSerialize(using = ToStringSerializer.class) long orders,
                               @JsonSerialize(using = ToStringSerializer.class) long units,
                               @JsonSerialize(using = ToStringSerializer.class) long orderValue) {
    @JsonProperty
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    public BigDecimal averageOrder() {
        // Без заказов нет знаменателя: null отличает неопределённое среднее от денежного нуля.
        return orders == 0 ? null
                : BigDecimal.valueOf(orderValue).divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
    }
}
