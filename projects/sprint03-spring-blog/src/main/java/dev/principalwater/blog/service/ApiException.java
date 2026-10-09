package dev.principalwater.blog.service;

import dev.principalwater.blog.model.BlogEntity;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(BlogEntity entity) {
        return new ApiException(HttpStatus.NOT_FOUND, entity.notFoundMessage());
    }

    public static ApiException badRequest(RequestError error) {
        return new ApiException(HttpStatus.BAD_REQUEST, error.message());
    }
}
