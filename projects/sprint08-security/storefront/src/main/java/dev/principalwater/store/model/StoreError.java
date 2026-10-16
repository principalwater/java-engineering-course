package dev.principalwater.store.model;

import org.springframework.http.HttpStatus;

public enum StoreError {
    INVALID_CATALOG(HttpStatus.BAD_REQUEST, "Invalid catalog"),
    CATALOG_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Catalog exceeds the import limit"),
    INVALID_PAGING(HttpStatus.BAD_REQUEST, "Invalid pagination parameters"),
    SEARCH_TOO_LONG(HttpStatus.BAD_REQUEST, "Search query exceeds the length limit"),
    INVALID_ACCOUNT(HttpStatus.BAD_REQUEST, "Invalid account details"),
    // Один ответ для логина и почты: форма не подтверждает, какой из двух адресов занят.
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "Username or email is already registered"),
    INVALID_RECOVERY_TOKEN(HttpStatus.BAD_REQUEST, "Recovery link is invalid or expired"),
    RECOVERY_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Too many password recovery requests are in progress; retry shortly"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request parameters"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart not found"),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "Product quantity must be an integer between 0 and " + StoreLimits.MAX_ITEM_QUANTITY),
    QUANTITY_LIMIT(HttpStatus.CONFLICT, "Product quantity exceeds the cart limit"),
    CART_LIMIT(HttpStatus.CONFLICT, "Cart contains too many different products"),
    STALE_CHECKOUT(HttpStatus.CONFLICT, "The cart or catalog changed; reload before confirming payment"),
    PAYMENT_PENDING(HttpStatus.SERVICE_UNAVAILABLE, "Payment outcome is pending; retry the same checkout"),
    INSUFFICIENT_FUNDS(HttpStatus.PAYMENT_REQUIRED, "Insufficient funds"),
    CHECKOUT_IN_PROGRESS(HttpStatus.CONFLICT, "Checkout is in progress; verify the payment before changing the cart"),
    EMPTY_CART(HttpStatus.CONFLICT, "Cannot purchase an empty cart"),
    ANALYTICS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Analytics is temporarily unavailable");

    private final HttpStatus status;
    private final String message;

    StoreError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}
