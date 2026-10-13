package dev.principalwater.store.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.principalwater.store.model.OrderLine;
import dev.principalwater.store.model.PurchaseOrder;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record AnalyticsFact(
        @JsonProperty("source_id") UUID sourceId,
        @JsonProperty("order_uuid") UUID orderUuid,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("line_number") int lineNumber,
        @JsonProperty("product_id") long productId,
        String sku,
        String title,
        long price,
        int quantity) {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    static AnalyticsFact from(UUID sourceId, PurchaseOrder order, OrderLine line) {
        return new AnalyticsFact(sourceId, order.getEventId(), order.getId(), TIMESTAMP.format(order.getCreatedAt()),
                line.getLineNumber(), line.getProductId(), line.getSku(), line.getTitle(), line.getPrice(), line.getQuantity());
    }
}
