package dev.principalwater.bank.contract;

import java.time.Instant;
import java.util.UUID;

public record OperationResult(
        UUID operationId,
        OperationStatus status,
        String error,
        UUID accountId,
        Long balanceMinor,
        Instant occurredAt) {}
