package dev.principalwater.store.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RecoveryRequestInput(
        @NotBlank @Size(max = AccountRules.EMAIL_MAX_LENGTH) @Pattern(regexp = AccountRules.EMAIL_PATTERN) String email) {
    // Адрес — персональные данные: он не попадает в сообщения об ошибках и журналы.
    @Override
    public String toString() {
        return "RecoveryRequestInput[]";
    }
}
