package dev.principalwater.payments.model;

import java.util.UUID;

public record PaymentDecision(UUID paymentId, DebitCommand command, long balanceMinor, PaymentStatus status) {
    public PaymentDecision {
        if (paymentId == null || command == null || status == null || balanceMinor < 0
                || balanceMinor > PaymentRules.MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Invalid persisted payment decision");
        }
    }
}
