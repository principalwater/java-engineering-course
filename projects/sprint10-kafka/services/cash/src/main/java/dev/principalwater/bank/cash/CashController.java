package dev.principalwater.bank.cash;

import dev.principalwater.bank.contract.CashRequest;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.runtime.security.UserIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CashController {
    private final CashService service;

    public CashController(CashService service) {
        this.service = service;
    }

    @PostMapping("/api/cash")
    public ResponseEntity<OperationResult> execute(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CashRequest request) {
        OperationResult result = service.execute(UserIdentity.actor(jwt), request);
        return ResponseEntity.status(
                        result.status() == OperationStatus.PENDING
                                ? HttpStatus.ACCEPTED
                                : HttpStatus.OK)
                .body(result);
    }
}
