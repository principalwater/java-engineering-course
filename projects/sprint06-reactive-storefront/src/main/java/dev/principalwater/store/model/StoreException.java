package dev.principalwater.store.model;

public class StoreException extends RuntimeException {
    private final StoreError error;

    public StoreException(StoreError error) {
        super(error.getMessage());
        this.error = error;
    }

    public StoreException(StoreError error, String detail) {
        super(error.getMessage() + ": " + detail);
        this.error = error;
    }

    public StoreException(StoreError error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
    }

    public StoreError getError() { return error; }
}
