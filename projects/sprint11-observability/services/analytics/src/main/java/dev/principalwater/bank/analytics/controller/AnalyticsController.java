package dev.principalwater.bank.analytics.controller;

import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.analytics.service.BusinessAnalyticsService;
import dev.principalwater.bank.analytics.service.PersonalAnalyticsService;
import dev.principalwater.bank.contract.UiActivityRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {
    private final PersonalAnalyticsService personal;
    private final BusinessAnalyticsService business;

    public AnalyticsController(
            PersonalAnalyticsService personal, BusinessAnalyticsService business) {
        this.personal = personal;
        this.business = business;
    }

    @GetMapping("/api/analytics")
    public AnalyticsView personal(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        return personal.personal(jwt, month);
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

    @PostMapping("/api/analytics/activities")
    public ResponseEntity<Void> activity(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UiActivityRequest request) {
        personal.activity(jwt, request);
        return ResponseEntity.noContent().build();
    }

    public record ViewRequest(@NotNull UUID eventId) {}
}
