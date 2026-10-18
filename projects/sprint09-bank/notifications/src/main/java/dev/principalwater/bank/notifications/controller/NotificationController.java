package dev.principalwater.bank.notifications.controller;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.NotificationView;
import dev.principalwater.bank.contract.Page;
import dev.principalwater.bank.notifications.service.NotificationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/internal/notifications/{owner}")
    public Page<NotificationView> inbox(
            @PathVariable UUID owner,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE) int limit) {
        return notifications.inbox(owner, before, limit);
    }

    @PostMapping("/internal/notifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BankEvent event) {
        notifications.receive(jwt.getClaimAsString(IdTokenClaimNames.AZP), event);
    }
}
