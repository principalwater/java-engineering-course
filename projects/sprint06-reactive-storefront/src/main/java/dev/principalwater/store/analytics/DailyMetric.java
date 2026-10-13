package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDate;

public record DailyMetric(LocalDate date, @JsonSerialize(using = ToStringSerializer.class) long orders, @JsonSerialize(using = ToStringSerializer.class) long units, @JsonSerialize(using = ToStringSerializer.class) long orderValue, int percent) {
}
