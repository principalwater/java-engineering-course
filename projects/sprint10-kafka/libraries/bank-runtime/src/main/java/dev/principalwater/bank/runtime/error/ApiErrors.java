package dev.principalwater.bank.runtime.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(BankException.class)
    public ResponseEntity<ErrorBody> domain(BankException exception) {
        return response(exception.getError());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        HandlerMethodValidationException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ErrorBody> invalid(Exception exception) {
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (frame.getClassName().startsWith("dev.principalwater.bank.")) {
                LOG.debug(
                        "Invalid request category={} location={}.{}",
                        exception.getClass().getSimpleName(),
                        frame.getClassName(),
                        frame.getMethodName());
                break;
            }
        }
        return response(BankError.INVALID_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorBody> storage(DataAccessException exception) {
        // SQL/параметры и чужие идентификаторы не попадают ни в ответ, ни в журнал.
        LOG.warn("Storage unavailable: {}", exception.getClass().getSimpleName());
        return response(BankError.UNAVAILABLE);
    }

    private ResponseEntity<ErrorBody> response(BankError error) {
        return ResponseEntity.status(error.getStatus())
                .body(new ErrorBody(error.name(), error.getMessage()));
    }

    public record ErrorBody(String code, String message) {}
}
