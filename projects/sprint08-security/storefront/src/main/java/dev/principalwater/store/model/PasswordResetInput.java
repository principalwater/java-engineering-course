package dev.principalwater.store.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetInput(
        @NotBlank @Pattern(regexp = AccountRules.RECOVERY_TOKEN_PATTERN) String token,
        @NotBlank @Size(min = AccountRules.PASSWORD_MIN_LENGTH, max = AccountRules.PASSWORD_MAX_BYTES) String password) {
    // Ни токен, ни пароль не должны попасть в журнал через автоматический toString записи.
    @Override
    public String toString() {
        return "PasswordResetInput[]";
    }
}
