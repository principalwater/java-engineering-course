package dev.principalwater.bank.contract;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record InternalOperation(@NotNull @Valid Actor actor, @NotNull UUID operationId,
                                @NotNull OperationType type,
                                @NotNull @Positive @Max(BankLimits.MAX_AMOUNT_MINOR)
                                @JsonDeserialize(using = StrictMinorDeserializer.class) Long amountMinor,
                                UUID recipientId) { }
