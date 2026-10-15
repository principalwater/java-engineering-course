package dev.principalwater.store.payment;

import java.util.UUID;

public record OrderPaymentView(UUID accountId, UUID paymentId, UUID referenceId, long amountMinor,
                               String currency, String status) { }
