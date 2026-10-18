package dev.principalwater.bank.runtime.workflow;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.runtime.client.RemoteUnavailable;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.delivery.RestOutbox;
import dev.principalwater.bank.runtime.error.BankException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public class MoneyWorkflow {
    private final IntentRepository intents;
    private final ServiceClient accounts;
    private final RestOutbox outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MoneyWorkflow(
            IntentRepository intents,
            ServiceClient accounts,
            RestOutbox outbox,
            TransactionTemplate transaction,
            Clock clock) {
        this.intents = intents;
        this.accounts = accounts;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
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
                        outbox.enqueue(
                                "notifications",
                                "/internal/notifications",
                                completionEvent(intent.operation(), completed, occurredAt));
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
                occurredAt);
    }
}
