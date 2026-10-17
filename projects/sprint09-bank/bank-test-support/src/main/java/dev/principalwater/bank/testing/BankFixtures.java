package dev.principalwater.bank.testing;

import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.contract.ProfileRequest;
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

    public static InternalOperation deposit(Actor owner, long amount) {
        return new InternalOperation(owner, UUID.randomUUID(), OperationType.DEPOSIT, amount, null);
    }
}
