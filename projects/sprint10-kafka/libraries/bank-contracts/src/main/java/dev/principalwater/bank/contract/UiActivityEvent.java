package dev.principalwater.bank.contract;

import java.time.Instant;
import java.util.UUID;

public record UiActivityEvent(
        UUID eventId,
        UUID ownerId,
        UiActivityType type,
        UiActivitySection section,
        Instant occurredAt) {
    public void validate() {
        new UiActivityRequest(eventId, type, section).validate();
        if (ownerId == null || occurredAt == null || occurredAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("Invalid UI activity identity or time");
        }
    }

    public boolean sameAction(UiActivityEvent other) {
        return other != null
                && eventId.equals(other.eventId)
                && ownerId.equals(other.ownerId)
                && type == other.type
                && section == other.section;
    }
}
