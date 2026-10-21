package dev.principalwater.bank.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @NotNull Instant occurredAt,
        @Size(max = 128) @JsonInclude(JsonInclude.Include.NON_NULL) String ownerLogin) {
    // Старые записи outbox остаются читаемыми; новые продюсеры передают логин для метрик доставки.
    public BankEvent(
            UUID eventId,
            UUID ownerId,
            String type,
            long amountMinor,
            long balanceMinor,
            Instant occurredAt) {
        this(eventId, ownerId, type, amountMinor, balanceMinor, occurredAt, null);
    }
}
