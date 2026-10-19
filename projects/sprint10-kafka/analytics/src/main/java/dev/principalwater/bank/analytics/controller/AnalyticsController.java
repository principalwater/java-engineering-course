package dev.principalwater.bank.analytics.controller;

import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.analytics.service.BusinessAnalyticsService;
import dev.principalwater.bank.analytics.service.PersonalAnalyticsService;
import dev.principalwater.bank.contract.BankEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {
    private final PersonalAnalyticsService personal;
    private final BusinessAnalyticsService business;
    private final AnalyticsInbox inbox;

    public AnalyticsController(
            PersonalAnalyticsService personal,
            BusinessAnalyticsService business,
            AnalyticsInbox inbox) {
        this.personal = personal;
        this.business = business;
        this.inbox = inbox;
    }

    @GetMapping("/api/analytics")
    public AnalyticsView personal(@AuthenticationPrincipal Jwt jwt) {
        return personal.personal(jwt);
    }

    @GetMapping("/api/analytics/business")
    public AnalyticsView business() {
        return business.business();
    }

    @PostMapping("/api/analytics/views")
    public ResponseEntity<Void> view(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ViewRequest view) {
        personal.view(jwt, view.eventId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/analytics/events")
    public ResponseEntity<Void> financial(@Valid @RequestBody BankEvent event) {
        inbox.acceptFinancial(event);
        return ResponseEntity.noContent().build();
    }

    public record ViewRequest(@NotNull UUID eventId) {}
}
