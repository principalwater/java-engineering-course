package dev.principalwater.store.analytics;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum AnalyticsPeriod {
    LAST_7_DAYS(7), LAST_30_DAYS(30), LAST_90_DAYS(90);

    public static final int MAXIMUM_DAYS = LAST_90_DAYS.getDays();
    private final int days;

    AnalyticsPeriod(int days) { this.days = days; }

    public int getDays() { return days; }

    public static AnalyticsPeriod fromDays(int days) {
        for (var period : values()) {
            if (period.days == days) {
                return period;
            }
        }
        throw new StoreException(StoreError.INVALID_REQUEST);
    }
}
