package dev.principalwater.bank.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ProfileRequest(
        @NotBlank @Size(max = BankLimits.MAX_FULL_NAME_LENGTH) String fullName,
        @NotNull LocalDate birthDate,
        @PositiveOrZero long version) {}
