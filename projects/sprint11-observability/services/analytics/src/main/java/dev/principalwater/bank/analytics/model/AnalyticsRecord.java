package dev.principalwater.bank.analytics.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.AnalyticsEventType;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.UiActivityEvent;
import dev.principalwater.bank.contract.UiActivityType;
import java.time.Instant;
import java.util.UUID;

public record AnalyticsRecord(BankEvent bank, UiActivityEvent ui) {
    public AnalyticsRecord {
        if ((bank == null) == (ui == null))
            throw new IllegalArgumentException("Invalid analytics record variant");
    }

    public UUID eventId() {
        return bank != null ? bank.eventId() : ui.eventId();
    }

    public UUID ownerId() {
        return bank != null ? bank.ownerId() : ui.ownerId();
    }

    public Instant occurredAt() {
        return bank != null ? bank.occurredAt() : ui.occurredAt();
    }

    public String type() {
        return bank != null ? bank.type() : ui.type().name();
    }

    public String encode(ObjectMapper mapper) throws JsonProcessingException {
        // Backend сохраняет прежний BankEvent JSON; UI имеет собственный тип без
        // денежных/персональных полей.
        return mapper.writeValueAsString(bank != null ? bank : ui);
    }

    public static AnalyticsRecord decode(String payload, ObjectMapper mapper)
            throws JsonProcessingException {
        String type = mapper.readTree(payload).path("type").asText();
        boolean ui =
                type.equals(UiActivityType.SCREEN_OPENED.name())
                        || type.equals(UiActivityType.FORM_OPENED.name());
        if (ui) {
            UiActivityEvent event =
                    mapper.readerFor(UiActivityEvent.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(payload);
            event.validate();
            return new AnalyticsRecord(null, event);
        }
        BankEvent event =
                mapper.readerFor(BankEvent.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(payload);
        if (event == null
                || event.eventId() == null
                || event.ownerId() == null
                || event.occurredAt() == null) {
            throw new IllegalArgumentException("Invalid analytics record identity");
        }
        AnalyticsEventType.valueOf(event.type());
        return new AnalyticsRecord(event, null);
    }
}
