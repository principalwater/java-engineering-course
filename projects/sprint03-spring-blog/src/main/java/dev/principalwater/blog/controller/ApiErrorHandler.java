package dev.principalwater.blog.controller;

import dev.principalwater.blog.model.BlogLimits;
import dev.principalwater.blog.service.ApiException;
import dev.principalwater.blog.service.RequestError;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorHandler.class);
    private static final String UPLOAD_TOO_LARGE_MESSAGE = "Максимальный размер изображения — %d МиБ"
            .formatted(BlogLimits.MAX_IMAGE_MEBIBYTES);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> applicationError(ApiException exception) {
        return error(exception.status(), exception.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, RequestError.INVALID_REQUEST.message());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE_MESSAGE);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (exception.getSupportedHttpMethods() != null) {
            response.allow(exception.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(Map.of("error", "Метод не поддерживается для этого адреса"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Формат тела запроса не поддерживается");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, String>> unacceptableMediaType(HttpMediaTypeNotAcceptableException exception) {
        return error(HttpStatus.NOT_ACCEPTABLE, "Формат ответа не поддерживается");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> resourceNotFound(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "Адрес не найден");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpectedError(Exception exception) {
        LOGGER.error("Request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось обработать запрос");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
