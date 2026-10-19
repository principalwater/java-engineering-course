package dev.principalwater.bank.front.controller;

import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.CashRequest;
import dev.principalwater.bank.contract.ProfileRequest;
import dev.principalwater.bank.contract.TransferRequest;
import dev.principalwater.bank.front.client.GatewayClient;
import dev.principalwater.bank.front.config.FrontOAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class BankController {
    private final GatewayClient gateway;

    public BankController(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/ui/account")
    public ResponseEntity<byte[]> account(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client) {
        return gateway.exchange(HttpMethod.GET, "/api/accounts/me", null, token(client));
    }

    @PutMapping("/ui/account")
    public ResponseEntity<byte[]> profile(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @Valid @RequestBody ProfileRequest request) {
        return gateway.exchange(HttpMethod.PUT, "/api/accounts/me", request, token(client));
    }

    @GetMapping("/ui/recipients")
    public ResponseEntity<byte[]> recipients(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "") @Size(max = BankLimits.MAX_RECIPIENT_SEARCH_LENGTH)
                    String search,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE)
                    @Min(1)
                    @Max(BankLimits.MAX_PAGE_SIZE)
                    int limit) {
        String path =
                UriComponentsBuilder.fromPath("/api/accounts/recipients")
                        .queryParam("limit", limit)
                        .queryParam("search", search)
                        .queryParamIfPresent("after", Optional.ofNullable(after))
                        .build()
                        .encode()
                        .toUriString();
        return gateway.exchange(HttpMethod.GET, path, null, token(client));
    }

    @GetMapping("/ui/history")
    public ResponseEntity<byte[]> history(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @RequestParam(required = false) @Min(1) Long before,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE)
                    @Min(1)
                    @Max(BankLimits.MAX_PAGE_SIZE)
                    int limit) {
        String path =
                UriComponentsBuilder.fromPath("/api/accounts/history")
                        .queryParam("limit", limit)
                        .queryParamIfPresent("before", Optional.ofNullable(before))
                        .build()
                        .encode()
                        .toUriString();
        return gateway.exchange(HttpMethod.GET, path, null, token(client));
    }

    @GetMapping("/ui/notifications")
    public ResponseEntity<byte[]> notifications(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @RequestParam(required = false) @Min(1) Long before,
            @RequestParam(defaultValue = BankLimits.DEFAULT_PAGE_SIZE)
                    @Min(1)
                    @Max(BankLimits.MAX_PAGE_SIZE)
                    int limit) {
        String path =
                UriComponentsBuilder.fromPath("/api/accounts/notifications")
                        .queryParam("limit", limit)
                        .queryParamIfPresent("before", Optional.ofNullable(before))
                        .build()
                        .encode()
                        .toUriString();
        return gateway.exchange(HttpMethod.GET, path, null, token(client));
    }

    @PostMapping("/ui/cash")
    public ResponseEntity<byte[]> cash(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @Valid @RequestBody CashRequest request) {
        return gateway.exchange(HttpMethod.POST, "/api/cash", request, token(client));
    }

    @PostMapping("/ui/transfers")
    public ResponseEntity<byte[]> transfer(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @Valid @RequestBody TransferRequest request) {
        return gateway.exchange(HttpMethod.POST, "/api/transfers", request, token(client));
    }

    @GetMapping("/ui/analytics")
    public ResponseEntity<byte[]> analytics(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client) {
        return gateway.exchange(HttpMethod.GET, "/api/analytics", null, token(client));
    }

    @GetMapping("/ui/analytics/business")
    public ResponseEntity<byte[]> business(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client) {
        return gateway.exchange(HttpMethod.GET, "/api/analytics/business", null, token(client));
    }

    @PostMapping("/ui/analytics/views")
    public ResponseEntity<byte[]> view(
            @RegisteredOAuth2AuthorizedClient(FrontOAuth.REGISTRATION_ID)
                    OAuth2AuthorizedClient client,
            @Valid @RequestBody ViewEvent request) {
        return gateway.exchange(HttpMethod.POST, "/api/analytics/views", request, token(client));
    }

    private String token(OAuth2AuthorizedClient client) {
        return client.getAccessToken().getTokenValue();
    }

    public record ViewEvent(@NotNull UUID eventId) {}
}
