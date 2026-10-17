package dev.principalwater.study.bank.ui;

import java.time.Duration;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Controller
class TransferPage {
    private static final Duration DEADLINE = Duration.ofSeconds(8);
    private final WebClient gateway;

    TransferPage(WebClient gatewayClient) {
        gateway = gatewayClient;
    }

    @GetMapping("/")
    String index() {
        return "index";
    }

    @GetMapping("/transfer")
    String form(Model model, @AuthenticationPrincipal OidcUser user) {
        model.addAttribute("username", user.getPreferredUsername());
        model.addAttribute("preview", gateway.get().uri("/api/transfers/preview").retrieve().bodyToMono(String.class).block(DEADLINE));
        return "transfer";
    }

    @PostMapping("/transfer")
    String submit(@Valid @ModelAttribute TransferRequest request, Model model) {
        model.addAttribute("result", gateway.post().uri("/api/transfers").bodyValue(request).retrieve()
                .bodyToMono(String.class).block(DEADLINE));
        return "result";
    }

    @ExceptionHandler(WebClientException.class)
    void failed(WebClientException exception) {
        HttpStatus status = exception instanceof WebClientResponseException response && response.getStatusCode().value() == 403
                ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        throw new ResponseStatusException(status, "Transfer request could not be completed");
    }
}
