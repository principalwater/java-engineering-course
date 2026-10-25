package dev.principalwater.bank.front.controller;

import dev.principalwater.bank.runtime.error.ApiErrors.ErrorBody;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthenticationErrors {
    private static final Set<String> REAUTHENTICATION =
            Set.of("client_authorization_required", "invalid_grant", "login_required");

    @ExceptionHandler(OAuth2AuthorizationException.class)
    public ResponseEntity<ErrorBody> oauthFailure(OAuth2AuthorizationException failure) {
        // XHR получает статус, а вход выполняется отдельной навигацией; тело ответа IdP не
        // раскрывается.
        return REAUTHENTICATION.contains(failure.getError().getErrorCode())
                ? ResponseEntity.status(401)
                        .body(new ErrorBody("AUTHENTICATION_REQUIRED", "Authentication required"))
                : ResponseEntity.status(503)
                        .body(new ErrorBody("UNAVAILABLE", "Authentication provider unavailable"));
    }
}
