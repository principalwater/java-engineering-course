package dev.principalwater.store.controller;

import dev.principalwater.store.model.AccountRegistration;
import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @ModelAttribute
    public void limits(Model model) {
        model.addAttribute("usernamePattern", AccountRules.USERNAME_PATTERN);
        model.addAttribute("usernameMinLength", AccountRules.USERNAME_MIN_LENGTH);
        model.addAttribute("usernameMaxLength", AccountRules.USERNAME_MAX_LENGTH);
        model.addAttribute("displayNameMaxLength", AccountRules.DISPLAY_NAME_MAX_LENGTH);
        model.addAttribute("passwordMinLength", AccountRules.PASSWORD_MIN_LENGTH);
        model.addAttribute("passwordMaxLength", AccountRules.PASSWORD_MAX_BYTES);
    }

    @GetMapping("/account/login")
    public String login(ServerWebExchange exchange, Model model) {
        // Thymeleaf param — lazy wrapper; наличие флага читаем из настоящей карты параметров WebFlux.
        model.addAttribute("loginError", exchange.getRequest().getQueryParams().containsKey("error"));
        model.addAttribute("registered", exchange.getRequest().getQueryParams().containsKey("registered"));
        return "account/login";
    }

    @GetMapping("/account/register")
    public String register(Model model) {
        model.addAttribute("registration", new AccountRegistration("", "", ""));
        return "account/register";
    }

    @PostMapping("/account/register")
    public Mono<Rendering> register(@Valid @ModelAttribute("registration") AccountRegistration registration,
                                    BindingResult validation) {
        if (validation.hasErrors()) {
            return Mono.just(registrationError(HttpStatus.BAD_REQUEST, "Проверьте имя, логин и длину пароля."));
        }
        return accounts.register(registration)
                .map(user -> Rendering.redirectTo("/account/login?registered").status(HttpStatus.SEE_OTHER).build())
                .onErrorResume(StoreException.class, failure -> {
                    if (failure.getError() == StoreError.ACCOUNT_EXISTS) {
                        return Mono.just(registrationError(HttpStatus.CONFLICT, "Этот логин уже занят. Выберите другой."));
                    }
                    if (failure.getError() == StoreError.INVALID_ACCOUNT) {
                        return Mono.just(registrationError(HttpStatus.BAD_REQUEST,
                                "Проверьте данные. Пароль должен занимать не более "
                                        + AccountRules.PASSWORD_MAX_BYTES + " байт в UTF-8."));
                    }
                    return Mono.error(failure);
                });
    }

    private Rendering registrationError(HttpStatus status, String message) {
        return Rendering.view("account/register").status(status).modelAttribute("accountError", message).build();
    }
}
