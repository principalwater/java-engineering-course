package dev.principalwater.bank.accounts.controller;

import dev.principalwater.bank.accounts.service.ProfileService;
import dev.principalwater.bank.contract.AccountView;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.HistoryEntry;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.contract.ProfileRequest;
import dev.principalwater.bank.contract.Recipient;
import dev.principalwater.bank.runtime.security.UserIdentity;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountsController {
    private final ProfileService profiles;

    public AccountsController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    public AccountView own(@AuthenticationPrincipal Jwt jwt) {
        return profiles.own(UserIdentity.actor(jwt));
    }

    @PutMapping("/me")
    public AccountView update(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        return profiles.update(UserIdentity.actor(jwt), request);
    }

    @GetMapping("/recipients")
    public Page<Recipient> recipients(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE) int limit) {
        return profiles.recipients(UserIdentity.actor(jwt), after, search, limit);
    }

    @GetMapping("/history")
    public Page<HistoryEntry> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE) int limit) {
        return profiles.history(UserIdentity.actor(jwt), before, limit);
    }
}
