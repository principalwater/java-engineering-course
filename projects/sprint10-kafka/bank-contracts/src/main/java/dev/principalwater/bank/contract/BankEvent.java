package dev.principalwater.bank.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record BankEvent(
        @NotNull UUID eventId,
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 40) String type,
        @JsonProperty(required = true)
                @JsonSetter(nulls = Nulls.FAIL)
                @JsonDeserialize(using = StrictMinorDeserializer.class)
                long amountMinor,
        @JsonProperty(required = true)
                @JsonSetter(nulls = Nulls.FAIL)
                @JsonDeserialize(using = StrictMinorDeserializer.class)
                long balanceMinor,
        @NotNull Instant occurredAt) {}
