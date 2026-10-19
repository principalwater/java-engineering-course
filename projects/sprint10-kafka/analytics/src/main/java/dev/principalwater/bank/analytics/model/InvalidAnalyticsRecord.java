package dev.principalwater.bank.analytics.model;

public final class InvalidAnalyticsRecord extends RuntimeException {
    public enum Reason {
        MALFORMED,
        KEY_MISMATCH,
        UNKNOWN_EVENT,
        PAYLOAD_MISMATCH
    }

    private final Reason reason;

    public InvalidAnalyticsRecord(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
