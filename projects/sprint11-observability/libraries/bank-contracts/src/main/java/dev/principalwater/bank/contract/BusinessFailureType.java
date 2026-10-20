package dev.principalwater.bank.contract;

public enum BusinessFailureType {
    WITHDRAWAL("bank.cash.withdrawal.failures", "cash"),
    TRANSFER("bank.transfer.failures", "transfer"),
    NOTIFICATION("bank.notification.failures", "notifications");

    private final String metricName;
    private final String sourceService;

    BusinessFailureType(String metricName, String sourceService) {
        this.metricName = metricName;
        this.sourceService = sourceService;
    }

    public String getMetricName() {
        return metricName;
    }

    public String getSourceService() {
        return sourceService;
    }
}
