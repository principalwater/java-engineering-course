package dev.principalwater.bank.analytics.model;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import java.time.Instant;

public enum AnalyticsEventType {
    DEPOSIT(1),
    WITHDRAW(-1),
    TRANSFER_IN(1),
    TRANSFER_OUT(-1),
    CABINET_VIEW(0);

    private final int sign;

    AnalyticsEventType(int sign) {
        this.sign = sign;
    }

    public int getSign() {
        return sign;
    }

    public static void validateFinancial(BankEvent event, Instant now) {
        AnalyticsEventType type = valueOf(event.type());
        if (type == CABINET_VIEW
                || event.amountMinor() == 0
                || event.amountMinor() < -BankLimits.MAX_AMOUNT_MINOR
                || event.amountMinor() > BankLimits.MAX_AMOUNT_MINOR
                || Long.signum(event.amountMinor()) != type.sign
                || event.balanceMinor() < 0
                || event.balanceMinor() > BankLimits.MAX_BALANCE_MINOR
                || event.occurredAt().isBefore(Instant.EPOCH)
                || event.occurredAt().isAfter(now.plusSeconds(60))) {
            throw new IllegalArgumentException("Invalid financial analytics event");
        }
    }
}
