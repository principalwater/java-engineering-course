package dev.principalwater.bank.analytics.model;

public enum AnalyticsProjection {
    PERSONAL,
    BUSINESS;

    public static final String TOPIC = "bank.analytics.v1";
    public static final String PERSONAL_GROUP = "bank.analytics.personal.v1";
    public static final String BUSINESS_GROUP = "bank.analytics.business.v1";

    public String getGroupId() {
        return this == PERSONAL ? PERSONAL_GROUP : BUSINESS_GROUP;
    }
}
