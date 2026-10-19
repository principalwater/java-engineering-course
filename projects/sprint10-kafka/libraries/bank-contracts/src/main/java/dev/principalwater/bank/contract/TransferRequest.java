package dev.principalwater.bank.contract;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID operationId,
        @NotNull UUID recipientId,
        @NotNull
                @Positive
                @Max(BankLimits.MAX_AMOUNT_MINOR)
                @JsonDeserialize(using = StrictMinorDeserializer.class)
                Long amountMinor) {}
