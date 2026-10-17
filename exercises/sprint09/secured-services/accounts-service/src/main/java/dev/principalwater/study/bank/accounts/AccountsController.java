package dev.principalwater.study.bank.accounts;

import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/accounts")
@PreAuthorize("hasRole('SERVICE') and authentication.tokenAttributes['azp'] == 'transfer-service'")
class AccountsController {
    private static final Map<String, String> OWNERS = Map.of("ACC-001", "test-user", "ACC-002", "other-user");

    record AccountOwner(String accountId, String ownerUsername) { }

    @GetMapping("/{accountId}/owner")
    AccountOwner owner(@PathVariable String accountId) {
        String owner = OWNERS.get(accountId);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return new AccountOwner(accountId, owner);
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasRole('SERVICE') and hasAuthority('accounts.write') and authentication.tokenAttributes['azp'] == 'transfer-service'")
    String transfer(@Valid @RequestBody TransferRequest request) {
        owner(request.fromAccountId());
        owner(request.toAccountId());
        // Упражнение проверяет identity и права; изменения денежных остатков здесь нет.
        return "Запрос принят: " + request.amount() + " со счёта " + request.fromAccountId() + " на счёт " + request.toAccountId();
    }
}
