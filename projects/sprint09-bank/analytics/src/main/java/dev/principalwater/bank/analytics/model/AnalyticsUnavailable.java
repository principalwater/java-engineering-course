package dev.principalwater.bank.analytics.model;

public final class AnalyticsUnavailable extends RuntimeException {
    public enum Category {
        CASSANDRA,
        WAREHOUSE,
        PROJECTION_PENDING,
        PROJECTION_LIMIT,
        INVALID_RESPONSE,
        IDENTITY,
        INBOX
    }

    private final Category category;

    public AnalyticsUnavailable(Category category) {
        super(category.name());
        this.category = category;
    }

    public AnalyticsUnavailable(Category category, Throwable cause) {
        super(category.name(), cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
