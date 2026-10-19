package dev.principalwater.bank.contract;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NotificationRead(@NotNull NotificationSource source, @NotNull UUID eventId) {}
