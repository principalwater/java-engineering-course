package dev.principalwater.store.model;

import org.springframework.http.HttpStatus;

public enum StoreError {
    INVALID_CATALOG(HttpStatus.BAD_REQUEST, "Invalid catalog"),
    CATALOG_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Catalog exceeds the import limit"),
    INVALID_PAGING(HttpStatus.BAD_REQUEST, "Invalid pagination parameters"),
    SEARCH_TOO_LONG(HttpStatus.BAD_REQUEST, "Search query exceeds the length limit"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request parameters"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart not found"),
    QUANTITY_LIMIT(HttpStatus.CONFLICT, "Product quantity exceeds the cart limit"),
    CART_LIMIT(HttpStatus.CONFLICT, "Cart contains too many different products"),
    EMPTY_CART(HttpStatus.CONFLICT, "Cannot purchase an empty cart");

    private final HttpStatus status;
    private final String message;

    StoreError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}
