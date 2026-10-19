package dev.principalwater.bank.analytics.model;

public enum AnalyticsProjection {
    PERSONAL,
    BUSINESS;

    public static final String TOPIC = "bank.analytics.v1";
    public static final String PERSONAL_GROUP = "bank.analytics.personal.v1";
    public static final String BUSINESS_GROUP = "bank.analytics.business.v1";
    public static final String ACCOUNTS_TOPIC =
            dev.principalwater.bank.contract.BankTopics.ACCOUNTS_ANALYTICS;
    public static final String INGRESS_GROUP = "bank.analytics.accounts.ingress.v1";

    public String getGroupId() {
        return switch (this) {
            case PERSONAL -> PERSONAL_GROUP;
            case BUSINESS -> BUSINESS_GROUP;
        };
    }
}
