package dev.principalwater.bank.accounts.controller;

import dev.principalwater.bank.accounts.service.ProfileService;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.OwnerReference;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final ProfileService profiles;

    public IdentityController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping("/internal/accounts/identity")
    public OwnerReference resolve(@Valid @RequestBody Actor actor) {
        // Аналитика получает только непрозрачный ключ, без профиля и чужого остатка.
        return new OwnerReference(profiles.own(actor).accountId());
    }
}
