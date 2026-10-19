package dev.principalwater.bank.accounts.controller;

import dev.principalwater.bank.accounts.service.AccountNotifications;
import dev.principalwater.bank.accounts.service.ProfileService;
import dev.principalwater.bank.contract.AccountView;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.HistoryEntry;
import dev.principalwater.bank.contract.NotificationInbox;
import dev.principalwater.bank.contract.NotificationRead;
import dev.principalwater.bank.contract.NotificationReadResult;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.contract.ProfileRequest;
import dev.principalwater.bank.contract.Recipient;
import dev.principalwater.bank.runtime.security.UserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountsController {
    private final ProfileService profiles;
    private final AccountNotifications notifications;

    public AccountsController(ProfileService profiles, AccountNotifications notifications) {
        this.profiles = profiles;
        this.notifications = notifications;
    }

    @GetMapping("/me")
    public AccountView own(@AuthenticationPrincipal Jwt jwt) {
        return profiles.own(UserIdentity.actor(jwt));
    }

    @GetMapping("/notifications")
    public NotificationInbox notifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Min(1) Long before,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE)
                    @Min(1)
                    @Max(BankLimits.MAX_PAGE_SIZE)
                    int limit) {
        return notifications.inbox(UserIdentity.actor(jwt), before, limit);
    }

    @PostMapping("/notifications/read")
    public NotificationReadResult readNotification(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NotificationRead request) {
        return notifications.read(UserIdentity.actor(jwt), request);
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
