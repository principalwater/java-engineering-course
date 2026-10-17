package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.accounts.dao.AccountDao;
import dev.principalwater.bank.accounts.dao.OperationDao;
import dev.principalwater.bank.accounts.model.Account;
import dev.principalwater.bank.accounts.model.MoneyRules;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.runtime.delivery.RestOutbox;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MoneyService {
    private final AccountDao accounts;
    private final OperationDao operations;
    private final RestOutbox outbox;
    private final Clock clock;
    private final boolean analyticsEnabled;

    public MoneyService(
            AccountDao accounts,
            OperationDao operations,
            RestOutbox outbox,
            Clock clock,
            @Value("${bank.analytics.enabled:false}") boolean analyticsEnabled) {
        this.accounts = accounts;
        this.operations = operations;
        this.outbox = outbox;
        this.clock = clock;
        this.analyticsEnabled = analyticsEnabled;
    }

    @Transactional
    public OperationResult execute(InternalOperation command) {
        validateTarget(command);
        Account identity = accounts.provision(command.actor());
        // Точность timestamptz — микросекунды: первый ответ и повтор должны совпадать.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!operations.reserve(identity.id(), command, now)) {
            return operations.replay(identity.id(), command);
        }
        Map<UUID, Account> locked = lockParticipants(identity.id(), command.recipientId());
        Account source = locked.get(identity.id());
        Account recipient = locked.get(command.recipientId());
        BankError rejection = MoneyRules.rejection(source, recipient, command);
        if (rejection != null) {
            return operations.finish(source.id(), command, rejection, source.balanceMinor(), now);
        }
        long delta =
                command.type() == OperationType.DEPOSIT
                        ? command.amountMinor()
                        : -command.amountMinor();
        long balance = Math.addExact(source.balanceMinor(), delta);
        String type =
                command.type() == OperationType.TRANSFER ? "TRANSFER_OUT" : command.type().name();
        apply(source.id(), source.id(), command, type, delta, balance, command.recipientId(), now);
        if (recipient != null) {
            apply(
                    recipient.id(),
                    source.id(),
                    command,
                    "TRANSFER_IN",
                    command.amountMinor(),
                    Math.addExact(recipient.balanceMinor(), command.amountMinor()),
                    source.id(),
                    now);
        }
        return operations.finish(source.id(), command, null, balance, now);
    }

    private Map<UUID, Account> lockParticipants(UUID source, UUID recipient) {
        // Одинаковый порядок блокировок предотвращает deadlock при встречных переводах.
        List<UUID> ids =
                recipient == null || recipient.equals(source)
                        ? List.of(source)
                        : List.of(source, recipient).stream().sorted().toList();
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : ids) {
            accounts.lock(id).ifPresent(account -> locked.put(id, account));
        }
        return locked;
    }

    private void apply(
            UUID owner,
            UUID commandOwner,
            InternalOperation command,
            String type,
            long delta,
            long balance,
            UUID counterparty,
            Instant now) {
        accounts.balance(owner, balance);
        accounts.ledger(
                owner,
                commandOwner,
                command.type().name(),
                command.operationId(),
                type,
                delta,
                balance,
                counterparty,
                now);
        if (analyticsEnabled) {
            String identity =
                    owner + ":" + commandOwner + ":" + command.type() + ":" + command.operationId();
            UUID eventId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            outbox.enqueue(
                    "analytics",
                    "/internal/analytics/events",
                    new BankEvent(eventId, owner, type, delta, balance, now));
        }
    }

    private void validateTarget(InternalOperation command) {
        boolean transfer = command.type() == OperationType.TRANSFER;
        if (transfer != (command.recipientId() != null)) {
            throw new BankException(BankError.INVALID_REQUEST);
        }
    }
}
