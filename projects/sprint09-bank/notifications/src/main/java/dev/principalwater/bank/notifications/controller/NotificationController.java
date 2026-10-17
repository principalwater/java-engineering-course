package dev.principalwater.bank.notifications.controller;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.notifications.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @PostMapping("/internal/notifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BankEvent event) {
        notifications.receive(jwt.getClaimAsString("azp"), event);
    }
}
