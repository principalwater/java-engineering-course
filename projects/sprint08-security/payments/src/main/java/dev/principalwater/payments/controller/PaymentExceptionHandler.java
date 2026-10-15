package dev.principalwater.payments.controller;

import dev.principalwater.payments.model.PaymentError;
import dev.principalwater.payments.model.PaymentException;
import jakarta.validation.ConstraintViolationException;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class PaymentExceptionHandler {
    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ProblemDetail> payment(PaymentException failure, ServerWebExchange exchange) {
        return response(failure.getError(), exchange);
    }

    @ExceptionHandler({ServerWebInputException.class, ConstraintViolationException.class, HandlerMethodValidationException.class,
            DataBufferLimitException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception failure, ServerWebExchange exchange) {
        return response(PaymentError.INVALID_REQUEST, exchange);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, TimeoutException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception failure, ServerWebExchange exchange) {
        return response(PaymentError.UNAVAILABLE, exchange);
    }

    private ResponseEntity<ProblemDetail> response(PaymentError error, ServerWebExchange exchange) {
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(PaymentProblems.body(error.getStatus(), error.getDetail(), exchange));
    }
}
