package dev.principalwater.study.bank.transfer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/transfers")
class TransferController {
    private final AccountsClient accounts;

    TransferController(AccountsClient accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasRole('USER')")
    String preview() {
        return "Проверка доступа к переводу";
    }

    @PostMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('transfer.write')")
    String submit(@Valid @RequestBody TransferRequest request, @AuthenticationPrincipal Jwt jwt) {
        try {
            if (!accounts.isOwner(request.fromAccountId(), jwt.getClaimAsString("preferred_username"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source account belongs to another user");
            }
            return accounts.submit(request);
        } catch (WebClientResponseException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        } catch (WebClientException | org.springframework.security.oauth2.core.OAuth2AuthorizationException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Accounts dependency unavailable");
        }
    }
}
