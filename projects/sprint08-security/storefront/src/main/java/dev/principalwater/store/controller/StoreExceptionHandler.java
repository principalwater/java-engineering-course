package dev.principalwater.store.controller;

import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.util.StorageFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@ControllerAdvice(basePackages = "dev.principalwater.store.controller")
public class StoreExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StoreExceptionHandler.class);

    @ExceptionHandler(StoreException.class)
    public Object domain(StoreException exception, ServerWebExchange exchange) {
        return error(exception.getError().getStatus(), exception.getMessage(), exception.getError(), exchange);
    }

    @ExceptionHandler({ServerWebInputException.class, HandlerMethodValidationException.class})
    public Object invalid(Exception exception, ServerWebExchange exchange) {
        return error(HttpStatus.BAD_REQUEST, StoreError.INVALID_REQUEST.getMessage(), exchange);
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public Object upload(DataBufferLimitException exception, ServerWebExchange exchange) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, StoreError.CATALOG_TOO_LARGE.getMessage(), exchange);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public Object conflict(Exception exception, ServerWebExchange exchange) {
        return error(HttpStatus.CONFLICT, "The catalog changed concurrently; reload and try again", exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Object status(ResponseStatusException exception, ServerWebExchange exchange) {
        return error(HttpStatus.valueOf(exception.getStatusCode().value()), "Request could not be completed", exchange);
    }

    @ExceptionHandler(Exception.class)
    public Object unexpected(Exception exception, ServerWebExchange exchange) {
        // Сбой модели/контроллера получает тот же прямой 503, что и сбой сессии в фильтрах, без Thymeleaf.
        if (StorageFailures.isUnavailable(exception)) {
            return Mono.error(exception);
        }
        LOG.error("Store request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", exchange);
    }

    private Object error(HttpStatus status, String message, ServerWebExchange exchange) {
        return error(status, message, null, exchange);
    }

    private Object error(HttpStatus status, String message, StoreError code, ServerWebExchange exchange) {
        if (exchange.getRequest().getHeaders().getAccept().stream().anyMatch(MediaType.APPLICATION_JSON::equalsTypeAndSubtype)) {
            var problem = ProblemDetail.forStatusAndDetail(status, message);
            if (code != null) {
                problem.setProperty("code", code.name());
            }
            return ResponseEntity.status(status).body(problem);
        }
        // Общий путь WebFlux позволяет ошибкам фильтра CSRF и контроллеров использовать один шаблон.
        var view = Rendering.view("error/error").status(status).modelAttribute("status", status.value())
                .modelAttribute("message", message);
        if (code != null) {
            view.modelAttribute("storeError", code);
        }
        return view.build();
    }
}
