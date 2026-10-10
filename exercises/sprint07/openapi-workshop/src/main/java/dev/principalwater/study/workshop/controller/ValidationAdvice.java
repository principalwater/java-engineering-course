package dev.principalwater.study.workshop.controller;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ValidationAdvice {
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail invalidParameters(ConstraintViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request parameters are invalid");
    }
}
