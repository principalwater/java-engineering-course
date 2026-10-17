package dev.principalwater.bank.runtime.error;

public enum BankError {
    INVALID_REQUEST(400, "Invalid request"),
    INVALID_PROFILE(422, "All profile fields are required and age must be over 18"),
    PROFILE_REQUIRED(422, "Complete the account profile first"),
    INSUFFICIENT_FUNDS(422, "Insufficient funds"),
    BALANCE_LIMIT(422, "Balance limit exceeded"),
    RECIPIENT_NOT_FOUND(422, "Recipient is unavailable"),
    SELF_TRANSFER(422, "Source and recipient must differ"),
    STALE_PROFILE(409, "Profile has changed; reload before saving"),
    OPERATION_CONFLICT(409, "Operation identifier belongs to another payload"),
    IDENTITY_CONFLICT(409, "Login is already linked to another identity"),
    UNAVAILABLE(503, "Service is temporarily unavailable");

    private final int status;
    private final String message;

    BankError(int status, String message) { this.status = status; this.message = message; }
    public int getStatus() { return status; }
    public String getMessage() { return message; }
}
