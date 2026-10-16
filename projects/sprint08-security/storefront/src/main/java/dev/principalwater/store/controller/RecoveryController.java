package dev.principalwater.store.controller;

import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.model.PasswordResetInput;
import dev.principalwater.store.model.RecoveryRequestInput;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.service.RecoveryService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
public class RecoveryController {
    private static final String REQUEST_VIEW = "account/recovery";
    private static final String RESET_VIEW = "account/recovery-reset";
    private static final String ERROR_ATTRIBUTE = "recoveryError";
    private final RecoveryService recovery;

    public RecoveryController(RecoveryService recovery) {
        this.recovery = recovery;
    }

    @ModelAttribute
    public void limits(Model model) {
        model.addAttribute("emailMaxLength", AccountRules.EMAIL_MAX_LENGTH);
        model.addAttribute("passwordMinLength", AccountRules.PASSWORD_MIN_LENGTH);
        model.addAttribute("passwordMaxLength", AccountRules.PASSWORD_MAX_BYTES);
        model.addAttribute("recoveryAvailable", recovery.available());
    }

    @GetMapping(StorePaths.RECOVERY)
    public Rendering requestForm(ServerWebExchange exchange) {
        boolean sent = exchange.getRequest().getQueryParams().containsKey("sent");
        return recovery.available() ? sensitive(Rendering.view(REQUEST_VIEW).modelAttribute("sent", sent)) : unavailable();
    }

    // Ответ одинаков для любого корректного адреса; перенаправление не кладёт адрес в URL.
    @PostMapping(StorePaths.RECOVERY)
    public Mono<Rendering> request(@Valid @ModelAttribute("recoveryRequest") RecoveryRequestInput input,
                                   BindingResult validation) {
        if (!recovery.available()) {
            return Mono.just(unavailable());
        }
        if (validation.hasErrors()) {
            return Mono.just(sensitive(Rendering.view(REQUEST_VIEW).status(HttpStatus.BAD_REQUEST)
                    .modelAttribute(ERROR_ATTRIBUTE, "Проверьте адрес почты.")));
        }
        return recovery.request(input.email()).thenReturn(sensitive(Rendering.redirectTo(StorePaths.RECOVERY + "?sent")
                .status(HttpStatus.SEE_OTHER)));
    }

    @GetMapping(StorePaths.RECOVERY_RESET)
    public Rendering resetForm(@RequestParam(required = false) String token) {
        if (!recovery.available()) {
            return unavailable();
        }
        if (token == null || !token.matches(AccountRules.RECOVERY_TOKEN_PATTERN)) {
            return invalidLink();
        }
        return sensitive(Rendering.view(RESET_VIEW).modelAttribute("reset", new PasswordResetInput(token, "")));
    }

    @PostMapping(StorePaths.RECOVERY_RESET)
    public Mono<Rendering> reset(@Valid @ModelAttribute("reset") PasswordResetInput input, BindingResult validation) {
        if (!recovery.available()) {
            return Mono.just(unavailable());
        }
        if (validation.hasErrors()) {
            return Mono.just(validation.hasFieldErrors("token") ? invalidLink() : passwordError());
        }
        return recovery.reset(input.token(), input.password())
                .thenReturn(sensitive(Rendering.redirectTo(StorePaths.LOGIN + "?passwordReset")
                        .status(HttpStatus.SEE_OTHER)))
                .onErrorResume(StoreException.class, failure -> {
                    if (failure.getError() == StoreError.INVALID_RECOVERY_TOKEN) {
                        return Mono.just(invalidLink());
                    }
                    return failure.getError() == StoreError.INVALID_ACCOUNT ? Mono.just(passwordError()) : Mono.error(failure);
                });
    }

    // Страница с токеном не кэшируется и не отдаёт Referer: адрес со ссылкой не уходит на другие сайты.
    private static Rendering sensitive(Rendering.Builder<?> builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .header(ReferrerPolicyServerHttpHeadersWriter.REFERRER_POLICY,
                        ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER.getPolicy()).build();
    }

    private Rendering unavailable() {
        return sensitive(Rendering.view(REQUEST_VIEW).status(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // Один ответ для неизвестной, просроченной и уже использованной ссылки.
    private Rendering invalidLink() {
        return sensitive(Rendering.view(RESET_VIEW).status(HttpStatus.BAD_REQUEST).modelAttribute("invalidLink", true));
    }

    private Rendering passwordError() {
        return sensitive(Rendering.view(RESET_VIEW).status(HttpStatus.BAD_REQUEST).modelAttribute(ERROR_ATTRIBUTE,
                "Пароль должен быть не короче " + AccountRules.PASSWORD_MIN_LENGTH + " символов и занимать не более "
                        + AccountRules.PASSWORD_MAX_BYTES + " байт в UTF-8."));
    }
}
