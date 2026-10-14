package dev.principalwater.payments.model;

import java.util.UUID;

public record DebitCommand(UUID accountId, UUID referenceId, long amountMinor, String currency) {
    public static final String CURRENCY = "RUB";

    public DebitCommand {
        if (accountId == null || referenceId == null || amountMinor < 1
                || amountMinor > PaymentRules.MAX_AMOUNT_MINOR || !CURRENCY.equals(currency)) {
            throw new PaymentException(PaymentError.INVALID_REQUEST);
        }
    }
}
