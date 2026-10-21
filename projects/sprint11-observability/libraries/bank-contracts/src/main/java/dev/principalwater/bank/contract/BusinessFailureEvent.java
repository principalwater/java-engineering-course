package dev.principalwater.bank.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record BusinessFailureEvent(
        @NotNull UUID eventId,
        @NotNull UUID ownerId,
        UUID counterpartyId,
        @NotNull BusinessFailureType type,
        @NotBlank @Size(max = 64) String reason,
        @PositiveOrZero long requestedAmountMinor,
        @NotNull Instant occurredAt) {}
