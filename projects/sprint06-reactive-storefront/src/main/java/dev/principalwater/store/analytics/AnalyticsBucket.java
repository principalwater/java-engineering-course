package dev.principalwater.store.analytics;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AnalyticsBucket {
    DAILY(1), PRODUCT(2), TOTAL(3);

    private final int code;

    AnalyticsBucket(int code) { this.code = code; }

    public int getCode() { return code; }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AnalyticsBucket fromCode(int code) {
        // GROUPING(day, sku) возвращает битовую маску отсутствующих ключей группировки.
        for (var bucket : values()) {
            if (bucket.code == code) {
                return bucket;
            }
        }
        throw new IllegalArgumentException("Unknown analytics grouping bucket");
    }
}
