package dev.principalwater.bank.accounts.controller;

import dev.principalwater.bank.accounts.service.MoneyService;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MoneyController {
    private final MoneyService money;

    public MoneyController(MoneyService money) {
        this.money = money;
    }

    @PostMapping("/internal/accounts/operations")
    public OperationResult execute(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InternalOperation command) {
        boolean transferCaller = "transfer-service".equals(jwt.getClaimAsString("azp"));
        if ((command.type() == OperationType.TRANSFER) != transferCaller) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Caller cannot perform this operation");
        }
        return money.execute(command);
    }
}
