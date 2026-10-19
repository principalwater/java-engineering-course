package dev.principalwater.bank.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record NotificationReceipt(
        @NotNull NotificationSource source, @NotNull @Valid BankEvent event) {}
