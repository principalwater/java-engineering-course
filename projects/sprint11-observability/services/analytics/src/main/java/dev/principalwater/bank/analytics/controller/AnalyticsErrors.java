package dev.principalwater.bank.analytics.controller;

import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.error.ApiErrors;
import dev.principalwater.bank.runtime.error.BankError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AnalyticsErrors {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsErrors.class);

    @ExceptionHandler({AnalyticsUnavailable.class, RemoteUnavailable.class})
    public ResponseEntity<ApiErrors.ErrorBody> unavailable(RuntimeException failure) {
        LOG.warn(
                "Analytics request unavailable: {}",
                failure instanceof AnalyticsUnavailable unavailable
                        ? unavailable.getCategory()
                        : AnalyticsUnavailable.Category.IDENTITY);
        return ResponseEntity.status(BankError.UNAVAILABLE.getStatus())
                .body(
                        new ApiErrors.ErrorBody(
                                BankError.UNAVAILABLE.name(), BankError.UNAVAILABLE.getMessage()));
    }
}
