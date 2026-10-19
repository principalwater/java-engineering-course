package dev.principalwater.bank.contract;

import java.util.UUID;

public record NotificationReadResult(UUID accountId, NotificationView notification) {}
