package dev.principalwater.bank.contract;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(
        UUID eventId, String type, long amountMinor, long balanceMinor, Instant occurredAt) {}
