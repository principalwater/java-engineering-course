package dev.principalwater.bank.runtime.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.delivery.RestOutbox;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import dev.principalwater.bank.testing.BankFixtures;
import dev.principalwater.bank.testing.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = MoneyWorkflowTest.DatabaseApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=workflow_test",
            "spring.config.import=classpath:bank-common.properties",
            "spring.flyway.locations=classpath:db/workflow,classpath:db/delivery"
        })
class MoneyWorkflowTest extends PostgresTestBase {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("0e3cd699-67b1-48a1-b60a-fae32a67ce42");
    @Autowired private PlatformTransactionManager transactions;
    private ServiceClient client;
    private IntentRepository intents;
    private RestOutbox outbox;
    private MoneyWorkflow workflow;

    @Override
    protected void resetDatabase() {
        jdbc.execute("TRUNCATE bank_intent, bank_outbox");
    }

    @BeforeEach
    void reset() {
        client = mock(ServiceClient.class);
        intents = new IntentRepository(jdbc, mapper);
        outbox = new RestOutbox(jdbc, mapper, client);
        workflow = restarted();
    }

    @Test
    void lostResponseRecoversSameCommandAndNotificationWithoutRepeatingCompletedCommand() {
        InternalOperation command = BankFixtures.deposit(BankFixtures.actor("alice"), 400);
        OperationResult success = success(command);
        when(client.post(eq("accounts"), any(), eq(command), eq(OperationResult.class)))
                .thenThrow(new RemoteUnavailable())
                .thenReturn(success);
        OperationResult pending = workflow.execute(command);
        assertThat(pending.status()).isEqualTo(OperationStatus.PENDING);
        assertThat(pending.balanceMinor()).isNull();
        jdbc.update("UPDATE bank_intent SET next_attempt_at = now() - interval '1 second'");
        MoneyWorkflow restarted = restarted();
        restarted.pendingIds().forEach(restarted::recover);
        assertThat(restarted.execute(command)).isEqualTo(success);
        assertThat(workflow.execute(command)).isEqualTo(success);
        verify(client, times(2))
                .post("accounts", "/internal/accounts/operations", command, OperationResult.class);

        when(client.post(eq("notifications"), any(), any(BankEvent.class), eq(Void.class)))
                .thenThrow(new RemoteUnavailable())
                .thenReturn(null);
        outbox.pendingIds().forEach(outbox::deliver);
        assertThat(jdbc.queryForObject("SELECT status FROM bank_outbox", String.class))
                .isEqualTo("PENDING");
        jdbc.update("UPDATE bank_outbox SET next_attempt_at = now() - interval '1 second'");
        RestOutbox restoredOutbox = new RestOutbox(jdbc, mapper, client);
        restoredOutbox.pendingIds().forEach(restoredOutbox::deliver);
        assertThat(jdbc.queryForObject("SELECT status FROM bank_outbox", String.class))
                .isEqualTo("DELIVERED");
        ArgumentCaptor<BankEvent> events = ArgumentCaptor.forClass(BankEvent.class);
        verify(client, times(2))
                .post(
                        eq("notifications"),
                        eq("/internal/notifications"),
                        events.capture(),
                        eq(Void.class));
        assertThat(events.getAllValues().get(0)).isEqualTo(events.getAllValues().get(1));
        assertThat(events.getValue().ownerId()).isEqualTo(ACCOUNT);
        verify(client, times(2))
                .post("accounts", "/internal/accounts/operations", command, OperationResult.class);
    }

    @Test
    void keyCannotChangePayloadAndSameIdRemainsIndependentForAnotherVerifiedActor() {
        Actor alice = BankFixtures.actor("alice");
        InternalOperation command = BankFixtures.deposit(alice, 100);
        when(client.post(
                        eq("accounts"),
                        any(),
                        any(InternalOperation.class),
                        eq(OperationResult.class)))
                .thenAnswer(call -> success(call.getArgument(2)));
        OperationResult original = workflow.execute(command);
        InternalOperation changed =
                new InternalOperation(alice, command.operationId(), command.type(), 101L, null);
        assertThatThrownBy(() -> workflow.execute(changed))
                .isInstanceOfSatisfying(
                        BankException.class,
                        exception ->
                                assertThat(exception.getError())
                                        .isEqualTo(BankError.OPERATION_CONFLICT));
        InternalOperation otherOwner =
                new InternalOperation(
                        BankFixtures.actor("bob"),
                        command.operationId(),
                        OperationType.DEPOSIT,
                        101L,
                        null);
        assertThat(workflow.execute(otherOwner).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(workflow.execute(command)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_intent", Integer.class))
                .isEqualTo(2);
        verify(client, times(2)).post(eq("accounts"), any(), any(), eq(OperationResult.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedDisplayLoginReplaysSameStableOwnerAndRetainsOriginalRecoveryActor(boolean pending) {
        Actor actor = BankFixtures.actor("alice");
        InternalOperation command = BankFixtures.deposit(actor, 100);
        OperationResult original = success(command);
        if (pending) {
            when(client.post(eq("accounts"), any(), eq(command), eq(OperationResult.class)))
                    .thenThrow(new RemoteUnavailable())
                    .thenReturn(original);
            assertThat(workflow.execute(command).status()).isEqualTo(OperationStatus.PENDING);
        } else {
            when(client.post(eq("accounts"), any(), eq(command), eq(OperationResult.class)))
                    .thenReturn(original);
            assertThat(workflow.execute(command)).isEqualTo(original);
        }
        InternalOperation renamed =
                new InternalOperation(
                        new Actor(actor.issuer(), actor.subject(), "renamed-alice"),
                        command.operationId(),
                        command.type(),
                        command.amountMinor(),
                        command.recipientId());
        if (pending) {
            jdbc.update("UPDATE bank_intent SET next_attempt_at = now() - interval '1 second'");
        }
        assertThat(restarted().execute(renamed)).isEqualTo(original);
        verify(client, times(pending ? 2 : 1))
                .post("accounts", "/internal/accounts/operations", command, OperationResult.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_intent", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->'actor'->>'login' FROM bank_intent", String.class))
                .isEqualTo(actor.login());
    }

    @Test
    void terminalBusinessRejectionReturnsOriginalErrorAndOwnBalance() {
        InternalOperation command =
                new InternalOperation(
                        BankFixtures.actor("alice"),
                        UUID.randomUUID(),
                        OperationType.WITHDRAW,
                        700L,
                        null);
        OperationResult rejected =
                new OperationResult(
                        command.operationId(),
                        OperationStatus.REJECTED,
                        "INSUFFICIENT_FUNDS",
                        ACCOUNT,
                        400L,
                        NOW);
        when(client.post(eq("accounts"), any(), eq(command), eq(OperationResult.class)))
                .thenReturn(rejected);
        assertThat(workflow.execute(command)).isEqualTo(rejected);
        assertThat(restarted().execute(command)).isEqualTo(rejected);
        verify(client)
                .post("accounts", "/internal/accounts/operations", command, OperationResult.class);
        assertThat(jdbc.queryForObject("SELECT payload->>'type' FROM bank_outbox", String.class))
                .isEqualTo("WITHDRAW_REJECTED");
    }

    @Test
    void localCompletionRollsBackWhenNotificationInsertFailsAndExpiredLeaseCanRecover() {
        InternalOperation command = BankFixtures.deposit(BankFixtures.actor("alice"), 100);
        when(client.post(eq("accounts"), any(), eq(command), eq(OperationResult.class)))
                .thenReturn(success(command));
        jdbc.execute("ALTER TABLE bank_outbox ADD CONSTRAINT fixture_reject_outbox CHECK (false)");
        try {
            assertThatThrownBy(() -> workflow.execute(command))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT result IS NULL FROM bank_intent", Boolean.class))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_outbox", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE bank_outbox DROP CONSTRAINT fixture_reject_outbox");
        }
        jdbc.update("UPDATE bank_intent SET lease_until = now() - interval '1 second'");
        MoneyWorkflow restarted = restarted();
        restarted.pendingIds().forEach(restarted::recover);
        assertThat(restarted.execute(command)).isEqualTo(success(command));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentReplayReturnsPendingWhileSingleLeaseOwnsNetworkCall() throws Exception {
        InternalOperation command = BankFixtures.deposit(BankFixtures.actor("alice"), 100);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.post(eq("accounts"), any(), eq(command), eq(OperationResult.class)))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out waiting for fixture release");
                            }
                            return success(command);
                        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<OperationResult> first = workers.submit(() -> workflow.execute(command));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<OperationResult> duplicate = workers.submit(() -> restarted().execute(command));
            assertThat(duplicate.get(3, TimeUnit.SECONDS).status())
                    .isEqualTo(OperationStatus.PENDING);
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(success(command));
            assertThat(workflow.execute(command)).isEqualTo(success(command));
            verify(client)
                    .post(
                            "accounts",
                            "/internal/accounts/operations",
                            command,
                            OperationResult.class);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private MoneyWorkflow restarted() {
        return new MoneyWorkflow(
                new IntentRepository(jdbc, mapper),
                client,
                outbox,
                new TransactionTemplate(transactions),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OperationResult success(InternalOperation command) {
        return new OperationResult(
                command.operationId(),
                OperationStatus.SUCCEEDED,
                null,
                ACCOUNT,
                command.amountMinor(),
                NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class DatabaseApp {}
}
