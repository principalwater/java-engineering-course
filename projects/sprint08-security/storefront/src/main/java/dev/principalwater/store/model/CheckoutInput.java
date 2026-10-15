package dev.principalwater.store.model;

import dev.principalwater.store.util.Money;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CheckoutInput(@NotNull UUID checkoutKey, @NotNull @Positive @Max(Money.MAX_MINOR) Long amountMinor) { }
