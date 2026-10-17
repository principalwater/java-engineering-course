package dev.principalwater.bank.accounts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.principalwater.bank.accounts.AccountsTestBase;
import dev.principalwater.bank.contract.AccountView;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.runtime.error.BankException;
import dev.principalwater.bank.testing.BankFixtures;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MoneyIntegrationTest extends AccountsTestBase {
    @Autowired private MoneyService money;
    @Autowired private ProfileService profiles;

    @Test
    void replayReturnsOriginalOutcomeEvenAfterLaterTransactions() {
        Actor alice = ready("alice");
        InternalOperation command = BankFixtures.deposit(alice, 10_000);
        OperationResult original = money.execute(command);
        money.execute(BankFixtures.deposit(alice, 5_000));
        assertEquals(original, money.execute(command));
        assertEquals(15_000, profiles.own(alice).balanceMinor());
        assertEquals(2, count("ledger_entry"));
    }

    @Test
    void terminalInsufficientFundsDoesNotTurnIntoDebitAfterDeposit() {
        Actor alice = ready("alice");
        InternalOperation command =
                new InternalOperation(alice, UUID.randomUUID(), OperationType.WITHDRAW, 100L, null);
        OperationResult rejected = money.execute(command);
        assertEquals("INSUFFICIENT_FUNDS", rejected.error());
        money.execute(BankFixtures.deposit(alice, 1_000));
        assertEquals(rejected, money.execute(command));
        assertEquals(1_000, profiles.own(alice).balanceMinor());
    }

    @Test
    void sameKeyWithDifferentPayloadConflictsWithoutApplyingSecondEffect() {
        Actor alice = ready("alice");
        InternalOperation command = BankFixtures.deposit(alice, 500);
        money.execute(command);
        assertThrows(
                BankException.class,
                () ->
                        money.execute(
                                new InternalOperation(
                                        alice, command.operationId(), command.type(), 501L, null)));
        assertEquals(500, profiles.own(alice).balanceMinor());
    }

    @Test
    void concurrentReplayDebitsOnceAndDifferentOwnersCanUseSameKey() throws Exception {
        Actor alice = ready("alice");
        Actor bob = ready("bob");
        InternalOperation command = BankFixtures.deposit(alice, 1_000);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Callable<OperationResult>> calls =
                    List.of(() -> money.execute(command), () -> money.execute(command));
            var results = workers.invokeAll(calls);
            assertEquals(results.getFirst().get(), results.getLast().get());
        }
        money.execute(
                new InternalOperation(bob, command.operationId(), command.type(), 200L, null));
        assertEquals(1_000, profiles.own(alice).balanceMinor());
        assertEquals(200, profiles.own(bob).balanceMinor());
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawAccount() throws Exception {
        Actor alice = ready("alice");
        money.execute(BankFixtures.deposit(alice, 100));
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Callable<OperationResult>> calls =
                    List.of(
                            () ->
                                    money.execute(
                                            new InternalOperation(
                                                    alice,
                                                    UUID.randomUUID(),
                                                    OperationType.WITHDRAW,
                                                    80L,
                                                    null)),
                            () ->
                                    money.execute(
                                            new InternalOperation(
                                                    alice,
                                                    UUID.randomUUID(),
                                                    OperationType.WITHDRAW,
                                                    80L,
                                                    null)));
            long succeeded =
                    workers.invokeAll(calls).stream()
                            .map(
                                    future -> {
                                        try {
                                            return future.get().status();
                                        } catch (Exception exception) {
                                            throw new AssertionError(exception);
                                        }
                                    })
                            .filter(OperationStatus.SUCCEEDED::equals)
                            .count();
            assertEquals(1, succeeded);
        }
        assertEquals(20, profiles.own(alice).balanceMinor());
    }

    @Test
    void transferAndBothLedgerEntriesRollbackWhenOutboxInsertFails() {
        Actor alice = ready("alice");
        Actor bob = ready("bob");
        money.execute(BankFixtures.deposit(alice, 1_000));
        jdbc.execute(
                "ALTER TABLE bank_outbox ADD CONSTRAINT reject_transfer_fixture CHECK (payload->>'type' <> 'TRANSFER_IN')");
        try {
            InternalOperation transfer =
                    new InternalOperation(
                            alice,
                            UUID.randomUUID(),
                            OperationType.TRANSFER,
                            200L,
                            profiles.own(bob).accountId());
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> money.execute(transfer));
            assertEquals(1_000, profiles.own(alice).balanceMinor());
            assertEquals(0, profiles.own(bob).balanceMinor());
            assertEquals(1, count("money_operation"));
            assertEquals(1, count("ledger_entry"));
        } finally {
            jdbc.execute("ALTER TABLE bank_outbox DROP CONSTRAINT reject_transfer_fixture");
        }
    }

    @Test
    void oppositeTransfersPreserveTotalAndOwnHistoryDoesNotExposeForeignBalance() throws Exception {
        Actor alice = ready("alice");
        Actor bob = ready("bob");
        money.execute(BankFixtures.deposit(alice, 10_000));
        money.execute(BankFixtures.deposit(bob, 10_000));
        AccountView a = profiles.own(alice);
        AccountView b = profiles.own(bob);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Callable<OperationResult>> calls =
                    List.of(
                            () ->
                                    money.execute(
                                            new InternalOperation(
                                                    alice,
                                                    UUID.randomUUID(),
                                                    OperationType.TRANSFER,
                                                    200L,
                                                    b.accountId())),
                            () ->
                                    money.execute(
                                            new InternalOperation(
                                                    bob,
                                                    UUID.randomUUID(),
                                                    OperationType.TRANSFER,
                                                    100L,
                                                    a.accountId())));
            for (var result : workers.invokeAll(calls)) {
                assertEquals(OperationStatus.SUCCEEDED, result.get().status());
            }
        }
        assertEquals(20_000, profiles.own(alice).balanceMinor() + profiles.own(bob).balanceMinor());
        var page = profiles.history(alice, null, 2);
        assertEquals(2, page.items().size());
        assertTrue(page.items().stream().allMatch(entry -> entry.type().startsWith("TRANSFER")));
        assertEquals(1, profiles.history(alice, page.nextCursor(), 2).items().size());
        assertEquals(1, profiles.recipients(alice, null, "", 20).items().size());
    }

    @Test
    void refusesBalanceOverflowAndIncompleteProfileWithoutLedgerWrites() {
        Actor alice = ready("alice");
        jdbc.update(
                "UPDATE account SET balance_minor=? WHERE id=?",
                BankLimits.MAX_BALANCE_MINOR,
                profiles.own(alice).accountId());
        assertEquals("BALANCE_LIMIT", money.execute(BankFixtures.deposit(alice, 1)).error());
        Actor fresh = BankFixtures.actor("fresh");
        assertEquals("PROFILE_REQUIRED", money.execute(BankFixtures.deposit(fresh, 1)).error());
        assertEquals(0, count("ledger_entry"));
    }

    private Actor ready(String login) {
        Actor actor = BankFixtures.actor(login);
        profiles.update(actor, BankFixtures.profile(login + " Example"));
        return actor;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
