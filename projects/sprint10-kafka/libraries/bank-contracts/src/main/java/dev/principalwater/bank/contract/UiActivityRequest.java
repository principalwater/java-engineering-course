package dev.principalwater.bank.contract;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UiActivityRequest(
        @NotNull UUID eventId, @NotNull UiActivityType type, @NotNull UiActivitySection section) {
    public void validate() {
        if (eventId == null
                || type == null
                || section == null
                || type == UiActivityType.FORM_OPENED
                        && section != UiActivitySection.PAYMENTS
                        && section != UiActivitySection.PROFILE
                || type == UiActivityType.SCREEN_OPENED && section == UiActivitySection.PAYMENTS) {
            throw new IllegalArgumentException("Invalid UI activity");
        }
    }
}
