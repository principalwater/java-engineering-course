package dev.principalwater.bank.testing;

import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.contract.ProfileRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class BankFixtures {
    private BankFixtures() {}

    public static Actor actor(String login) {
        return new Actor("https://fixture.bank", "subject-" + login, login);
    }

    public static ProfileRequest profile(String name) {
        return new ProfileRequest(name, LocalDate.of(1990, 6, 15), 0);
    }

    public static OperationResult succeeded(UUID operationId, long balanceMinor) {
        return new OperationResult(
                operationId,
                OperationStatus.SUCCEEDED,
                null,
                UUID.randomUUID(),
                balanceMinor,
                Instant.parse("2026-10-17T12:00:00Z"));
    }

    public static OperationResult pending(UUID operationId) {
        return new OperationResult(operationId, OperationStatus.PENDING, null, null, null, null);
    }

    public static InternalOperation deposit(Actor owner, long amount) {
        return new InternalOperation(owner, UUID.randomUUID(), OperationType.DEPOSIT, amount, null);
    }
}
