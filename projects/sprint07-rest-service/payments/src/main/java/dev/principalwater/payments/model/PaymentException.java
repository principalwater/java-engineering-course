package dev.principalwater.payments.model;

public class PaymentException extends RuntimeException {
    private final PaymentError error;

    public PaymentException(PaymentError error) {
        super(error.getDetail());
        this.error = error;
    }

    public PaymentError getError() {
        return error;
    }
}
