package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.accounts.model.Account;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.BusinessFailureEvent;
import dev.principalwater.bank.contract.BusinessFailureType;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.runtime.error.BankError;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MoneySignals {
    private static final Logger LOG = LoggerFactory.getLogger(MoneySignals.class);
    private final MeterRegistry meters;
    private final ApplicationEventPublisher events;
    private final KafkaOutbox outbox;
    private final boolean analyticsEnabled;

    public MoneySignals(
            MeterRegistry meters,
            ApplicationEventPublisher events,
            KafkaOutbox outbox,
            @Value("${bank.analytics.enabled:false}") boolean analyticsEnabled) {
        this.meters = meters;
        this.events = events;
        this.outbox = outbox;
        this.analyticsEnabled = analyticsEnabled;
    }

    public void accepted(OperationType type) {
        events.publishEvent(new CommittedOperation(type));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committedOperation(CommittedOperation operation) {
        // Не публикуем успех до commit; replay и rollback не создают повторного сообщения.
        LOG.info("Financial operation committed: type={}", operation.type());
    }

    public void rejected(
            InternalOperation command,
            Account owner,
            Account recipient,
            BankError reason,
            Instant at) {
        if (command.type() == OperationType.DEPOSIT) return;
        BusinessFailureType type =
                command.type() == OperationType.TRANSFER
                        ? BusinessFailureType.TRANSFER
                        : BusinessFailureType.WITHDRAWAL;
        UUID id =
                UUID.nameUUIDFromBytes(
                        ("bank.failure.v1:"
                                        + owner.id()
                                        + ":"
                                        + command.type()
                                        + ":"
                                        + command.operationId())
                                .getBytes(StandardCharsets.UTF_8));
        BusinessFailureEvent event =
                new BusinessFailureEvent(
                        id,
                        owner.id(),
                        command.recipientId(),
                        type,
                        reason.name(),
                        command.amountMinor(),
                        at);
        if (analyticsEnabled) {
            // Отказ и его аналитический факт фиксируются одной транзакцией; replay не создаёт новый
            // факт.
            outbox.enqueue(
                    BankTopics.BUSINESS_FAILURES, owner.id().toString(), id.toString(), event);
        }
        events.publishEvent(
                new CommittedFailure(
                        event, owner.login(), recipient == null ? "_unknown" : recipient.login()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(CommittedFailure failure) {
        try {
            countCommitted(failure);
        } catch (RuntimeException unavailable) {
            // Решение уже зафиксировано: отказ метрик не должен превращать его в HTTP-ошибку.
            LOG.warn(
                    "Financial failure telemetry unavailable: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    private void countCommitted(CommittedFailure failure) {
        BusinessFailureEvent event = failure.event();
        Counter.Builder counter =
                Counter.builder(event.type().getMetricName()).tag("reason", event.reason());
        if (event.type() == BusinessFailureType.TRANSFER) {
            counter.tags("sender", failure.login(), "recipient", failure.recipient());
        } else {
            counter.tag("login", failure.login());
        }
        counter.register(meters).increment();
        // Идентификаторы и суммы остаются в защищённом аналитическом контуре, а не в общем логе.
        LOG.info("Financial operation rejected: type={} reason={}", event.type(), event.reason());
    }

    public record CommittedOperation(OperationType type) {}

    public record CommittedFailure(BusinessFailureEvent event, String login, String recipient) {}
}
