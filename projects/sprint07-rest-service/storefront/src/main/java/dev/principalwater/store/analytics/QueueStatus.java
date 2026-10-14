package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;

public record QueueStatus(@JsonSerialize(using = ToStringSerializer.class) long pendingOrders,
                          Instant lastDelivery, Instant oldestPending) {
}
