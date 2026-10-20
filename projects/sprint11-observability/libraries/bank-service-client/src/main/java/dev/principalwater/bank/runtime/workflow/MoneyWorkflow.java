package dev.principalwater.bank.runtime.workflow;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.messaging.RecoverableDelivery;
import dev.principalwater.bank.observability.DeferredTrace;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.error.BankException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public class MoneyWorkflow implements RecoverableDelivery {
    private final IntentRepository intents;
    private final ServiceClient accounts;
    private final KafkaOutbox outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final DeferredTrace trace;

    public MoneyWorkflow(
            IntentRepository intents,
            ServiceClient accounts,
            KafkaOutbox outbox,
            TransactionTemplate transaction,
            Clock clock) {
        this(intents, accounts, outbox, transaction, clock, DeferredTrace.disabled());
    }

    public MoneyWorkflow(
            IntentRepository intents,
            ServiceClient accounts,
            KafkaOutbox outbox,
            TransactionTemplate transaction,
            Clock clock,
            DeferredTrace trace) {
        this.intents = intents;
        this.accounts = accounts;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
        this.trace = trace;
    }

    public OperationResult execute(InternalOperation operation) {
        IntentRepository.Intent intent = intents.reserve(operation);
        if (intent.result() != null) {
            return intent.result();
        }
        process(intent.id());
        OperationResult result = intents.find(intent.id()).result();
        return result == null
                ? new OperationResult(
                        operation.operationId(), OperationStatus.PENDING, null, null, null, null)
                : result;
    }

    public List<UUID> pendingIds() {
        return intents.due();
    }

    public void recover(UUID id) {
        process(id);
    }

    private void process(UUID id) {
        IntentRepository.Intent intent = intents.claim(id);
        if (intent == null) {
            return;
        }
        // Аренда может перейти к другому процессу: продолжаем сохранённую трассу, а не фоновую.
        try (DeferredTrace.Relay ignored =
                trace.resume(intent.parent(), "bank.money.workflow", "accounts")) {
            processClaimed(intent);
        }
    }

    private void processClaimed(IntentRepository.Intent intent) {
        OperationResult result;
        try {
            // Здесь нет открытой JDBC-транзакции; downstream ID и actor сохранены до HTTP.
            result =
                    accounts.post(
                            "accounts",
                            "/internal/accounts/operations",
                            intent.operation(),
                            OperationResult.class);
            if (result == null
                    || !intent.operation().operationId().equals(result.operationId())
                    || result.status() == null
                    || result.status() == OperationStatus.PENDING) {
                throw new RemoteUnavailable();
            }
        } catch (BankException exception) {
            result =
                    new OperationResult(
                            intent.operation().operationId(),
                            OperationStatus.REJECTED,
                            exception.getError().name(),
                            null,
                            null,
                            clock.instant());
        } catch (RemoteUnavailable exception) {
            intents.defer(intent);
            return;
        }
        OperationResult completed = result;
        transaction.executeWithoutResult(
                status -> {
                    if (intents.complete(intent, completed)
                            && completed.accountId() != null
                            && completed.balanceMinor() != null) {
                        Instant occurredAt =
                                completed.occurredAt() == null
                                        ? clock.instant()
                                        : completed.occurredAt();
                        BankEvent event =
                                completionEvent(intent.operation(), completed, occurredAt);
                        NotificationSource source =
                                intent.operation().type() == OperationType.TRANSFER
                                        ? NotificationSource.TRANSFER
                                        : NotificationSource.CASH;
                        outbox.enqueue(
                                source.getTopic(),
                                event.ownerId().toString(),
                                event.eventId().toString(),
                                event);
                    }
                });
    }

    private BankEvent completionEvent(
            InternalOperation command, OperationResult result, Instant occurredAt) {
        String key =
                command.actor().issuer().length()
                        + ":"
                        + command.actor().issuer()
                        + command.actor().subject().length()
                        + ":"
                        + command.actor().subject()
                        + ":"
                        + command.type()
                        + ":"
                        + command.operationId();
        UUID eventId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return new BankEvent(
                eventId,
                result.accountId(),
                command.type().name() + "_" + result.status().name(),
                command.amountMinor(),
                result.balanceMinor(),
                occurredAt,
                command.actor().login());
    }
}
