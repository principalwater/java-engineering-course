package dev.principalwater.bank.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record BankEvent(@NotNull UUID eventId, @NotNull UUID ownerId,
                        @NotBlank @Size(max = 40) String type, long amountMinor,
                        long balanceMinor, @NotNull Instant occurredAt) { }
