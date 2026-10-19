package dev.principalwater.bank.runtime.error;

public final class BankException extends RuntimeException {
    private final BankError error;

    public BankException(BankError error) {
        super(error.getMessage());
        this.error = error;
    }

    public BankError getError() {
        return error;
    }
}
