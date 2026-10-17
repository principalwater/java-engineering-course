package dev.principalwater.payments.model;

public record PaymentResult(PaymentDecision decision, boolean replay) {
}
