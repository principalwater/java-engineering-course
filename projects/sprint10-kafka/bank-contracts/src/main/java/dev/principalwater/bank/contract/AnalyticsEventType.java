package dev.principalwater.bank.contract;

import java.time.Instant;

public enum AnalyticsEventType {
    DEPOSIT(1),
    WITHDRAW(-1),
    TRANSFER_IN(1),
    TRANSFER_OUT(-1),
    CABINET_VIEW(0),
    PROFILE_UPDATED(0),
    NOTIFICATION_AVAILABLE(0),
    NOTIFICATION_READ(0);

    private final int sign;

    AnalyticsEventType(int sign) {
        this.sign = sign;
    }

    public int getSign() {
        return sign;
    }

    public boolean isFinancial() {
        return sign != 0;
    }

    public static void validateBackend(BankEvent event, Instant now) {
        if (event == null || event.type() == null)
            throw new IllegalArgumentException("Invalid backend analytics type");
        AnalyticsEventType type = valueOf(event.type());
        if (type.isFinancial()) {
            validateFinancial(event, now);
        } else if (type == CABINET_VIEW || event.amountMinor() != 0 || event.balanceMinor() != 0) {
            throw new IllegalArgumentException("Invalid backend analytics event");
        }
        validateIdentityAndTime(event, now);
    }

    public static void validateFinancial(BankEvent event, Instant now) {
        if (event == null || event.type() == null)
            throw new IllegalArgumentException("Invalid financial analytics type");
        AnalyticsEventType type = valueOf(event.type());
        if (!type.isFinancial()
                || event.amountMinor() == 0
                || event.amountMinor() < -BankLimits.MAX_AMOUNT_MINOR
                || event.amountMinor() > BankLimits.MAX_AMOUNT_MINOR
                || Long.signum(event.amountMinor()) != type.sign
                || event.balanceMinor() < 0
                || event.balanceMinor() > BankLimits.MAX_BALANCE_MINOR) {
            throw new IllegalArgumentException("Invalid financial analytics event");
        }
        validateIdentityAndTime(event, now);
    }

    private static void validateIdentityAndTime(BankEvent event, Instant now) {
        if (event.eventId() == null
                || event.ownerId() == null
                || event.occurredAt() == null
                || event.occurredAt().isBefore(Instant.EPOCH)
                || event.occurredAt().isAfter(now.plusSeconds(60))) {
            throw new IllegalArgumentException("Invalid analytics identity or time");
        }
    }
}
