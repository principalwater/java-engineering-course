package dev.principalwater.store.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountRegistration(
        @NotBlank @Pattern(regexp = AccountRules.USERNAME_PATTERN) String username,
        @NotBlank @Size(max = AccountRules.DISPLAY_NAME_MAX_LENGTH) String displayName,
        @NotBlank @Size(max = AccountRules.EMAIL_MAX_LENGTH) @Pattern(regexp = AccountRules.EMAIL_PATTERN) String email,
        @NotBlank @Size(min = AccountRules.PASSWORD_MIN_LENGTH, max = AccountRules.PASSWORD_MAX_BYTES) String password) {
    // Пароль и почта (персональные данные) не попадают в логи и сообщения.
    @Override
    public String toString() {
        return "AccountRegistration[username=" + username + "]";
    }
}
