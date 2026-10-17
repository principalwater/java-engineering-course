package dev.principalwater.bank.contract;

import java.time.Instant;
import java.util.UUID;

public record HistoryEntry(UUID operationId, String type, long amountMinor, long balanceMinor,
                           OperationStatus status, Instant occurredAt, String counterparty) { }
