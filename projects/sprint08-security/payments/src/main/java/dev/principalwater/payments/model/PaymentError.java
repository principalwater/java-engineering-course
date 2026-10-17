package dev.principalwater.payments.model;

import org.springframework.http.HttpStatus;

public enum PaymentError {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid payment request"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Payment key or account reference belongs to another request"),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment outcome is unavailable; retry with the same idempotency key");

    private final HttpStatus status;
    private final String detail;

    PaymentError(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus getStatus() { return status; }
    public String getDetail() { return detail; }
}
