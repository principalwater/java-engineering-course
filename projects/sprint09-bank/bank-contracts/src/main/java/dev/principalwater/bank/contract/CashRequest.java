package dev.principalwater.bank.contract;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CashRequest(@NotNull UUID operationId, @NotNull OperationType type,
                          @NotNull @Positive @Max(BankLimits.MAX_AMOUNT_MINOR)
                          @JsonDeserialize(using = StrictMinorDeserializer.class) Long amountMinor) { }
