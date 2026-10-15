package dev.principalwater.payments.model;

public enum PaymentStatus {
    SUCCEEDED, DECLINED;

    public String getValue() {
        return name();
    }
}
