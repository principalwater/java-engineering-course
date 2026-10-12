package dev.principalwater.store.controller;

import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(basePackages = "dev.principalwater.store.controller")
public class StoreExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(StoreExceptionHandler.class);

    @ExceptionHandler(StoreException.class)
    public ModelAndView domain(StoreException exception) {
        return error(exception.getError().getStatus(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class,
            HandlerMethodValidationException.class})
    public ModelAndView invalid(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, StoreError.INVALID_REQUEST.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView upload(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, StoreError.CATALOG_TOO_LARGE.getMessage());
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ModelAndView conflict(Exception exception) {
        return error(HttpStatus.CONFLICT, "The catalog changed concurrently; reload and try again");
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView unexpected(Exception exception) {
        LOG.error("Store request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ModelAndView error(HttpStatus status, String message) {
        var result = new ModelAndView("error");
        result.setStatus(status);
        result.addObject("status", status.value());
        result.addObject("message", message);
        return result;
    }
}
