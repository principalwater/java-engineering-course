package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalyticsRow(
        AnalyticsBucket bucket,
        String day,
        String sku,
        String title,
        @JsonSerialize(using = ToStringSerializer.class) long orders,
        @JsonSerialize(using = ToStringSerializer.class) long units,
        @JsonProperty("order_value") @JsonSerialize(using = ToStringSerializer.class) long orderValue) {
    public AnalyticsRow {
        if (bucket == null || day == null || sku == null || title == null
                || orders < 0 || units < 0 || orderValue < 0) {
            throw new IllegalArgumentException("Invalid analytics aggregate response");
        }
    }
}
