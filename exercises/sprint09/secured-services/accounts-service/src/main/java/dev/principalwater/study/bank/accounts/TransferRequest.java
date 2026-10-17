package dev.principalwater.study.bank.accounts;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

public record TransferRequest(
        @Pattern(regexp = "ACC-\\d{3}") @jakarta.validation.constraints.NotNull String fromAccountId,
        @Pattern(regexp = "ACC-\\d{3}") @jakarta.validation.constraints.NotNull String toAccountId,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @jakarta.validation.constraints.NotNull BigDecimal amount) {
}
