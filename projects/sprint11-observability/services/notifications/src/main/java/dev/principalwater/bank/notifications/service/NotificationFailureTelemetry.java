package dev.principalwater.bank.notifications.service;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.BusinessFailureEvent;
import dev.principalwater.bank.contract.BusinessFailureType;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.messaging.MessageCodec;
import dev.principalwater.bank.messaging.MessageRejected;
import dev.principalwater.bank.messaging.OutboxDeliveryFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NotificationFailureTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationFailureTelemetry.class);
    // Старое событие без логина и нераспознанное тело не теряют счётчик: метка ограничена.
    private static final String UNKNOWN_LOGIN = "_unknown";
    private static final String FACT_GAP_METER = "bank.notification.failure.fact.gaps";
    private final MeterRegistry meters;
    private final MessageCodec codec;
    private final KafkaOutbox outbox;
    private final TransactionTemplate transaction;
    private final boolean analyticsEnabled;

    public NotificationFailureTelemetry(
            MeterRegistry meters,
            MessageCodec codec,
            KafkaOutbox outbox,
            PlatformTransactionManager transactions,
            @Value("${bank.analytics.enabled:false}") boolean analyticsEnabled) {
        this.meters = meters;
        this.codec = codec;
        this.outbox = outbox;
        this.analyticsEnabled = analyticsEnabled;
        // Факт отказа фиксируется своей малой транзакцией и не зависит от вызывающего контекста.
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Событие приходит уже после сохранённого повтора, в потоке воркера и вне транзакции, поэтому
    // обычный слушатель: сбой телеметрии не может отменить повтор или подтвердить доставку.
    @EventListener
    public void deliveryFailed(OutboxDeliveryFailure delivery) {
        // Отказ публикации самого факта едет в другой топик и сюда не попадает: рекурсии нет.
        if (!BankTopics.NOTIFICATION_RECEIPTS.equals(delivery.topic())) return;
        NotificationReceipt receipt = decode(delivery.payload());
        BankEvent event = receipt == null ? null : receipt.event();
        countFailure(event, Reason.RECEIPT_PUBLISH_FAILED);
        if (analyticsEnabled && event != null) recordFact(delivery.outboxId(), event);
    }

    // Вызывается до повторного выброса исходной ошибки слушателя; trusted — событие, прошедшее
    // проверку ключа, иначе логин остаётся неизвестным.
    public void receiveFailed(BankEvent trusted, RuntimeException failure) {
        Reason reason =
                failure instanceof MessageRejected
                        ? Reason.EVENT_REJECTED
                        : Reason.RECEIVE_UNAVAILABLE;
        countFailure(trusted, reason);
    }

    private NotificationReceipt decode(String payload) {
        try {
            return codec.decode(payload, NotificationReceipt.class);
        } catch (MessageRejected invalid) {
            return null;
        }
    }

    private void countFailure(BankEvent event, Reason reason) {
        try {
            Counter.builder(BusinessFailureType.NOTIFICATION.getMetricName())
                    .tag("login", login(event))
                    .tag("reason", reason.getLabel())
                    .register(meters)
                    .increment();
            LOG.warn("Notification delivery failed: reason={}", reason.getLabel());
        } catch (RuntimeException unavailable) {
            // Метрика вторична: она не должна подменить исходную ошибку доставки своей.
            LOG.warn(
                    "Notification failure telemetry unavailable: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    private void recordFact(UUID outboxId, BankEvent event) {
        // Идентификатор зависит только от записи outbox: повторные неудачные попытки той же
        // публикации дают идентичный факт и не плодят новые.
        String seed =
                "bank.failure.v1:"
                        + event.ownerId()
                        + ":"
                        + BusinessFailureType.NOTIFICATION
                        + ":"
                        + outboxId;
        UUID id = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        // Время исходного уведомления, а не попытки: outbox не принимает тот же идентификатор с
        // другим содержимым. Сумма 0: деньги остаются в ledger, а недоставка уведомления — не
        // финансовый отказ.
        BusinessFailureEvent fact =
                new BusinessFailureEvent(
                        id,
                        event.ownerId(),
                        null,
                        BusinessFailureType.NOTIFICATION,
                        Reason.RECEIPT_PUBLISH_FAILED.getLabel(),
                        0,
                        event.occurredAt());
        try {
            transaction.executeWithoutResult(
                    status ->
                            outbox.enqueue(
                                    BankTopics.BUSINESS_FAILURES,
                                    event.ownerId().toString(),
                                    id.toString(),
                                    fact));
        } catch (RuntimeException unavailable) {
            // БД недоступна: повтор доставки уже сохранён, а пропуск факта виден счётчиком.
            // Следующая неудачная попытка той же записи повторит вставку с тем же идентификатором.
            Counter.builder(FACT_GAP_METER)
                    .description("Notification failure facts not recorded for analytics")
                    .register(meters)
                    .increment();
            LOG.warn(
                    "Notification failure fact unavailable: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    private static String login(BankEvent event) {
        String login = event == null ? null : event.ownerLogin();
        return login == null || login.isBlank() ? UNKNOWN_LOGIN : login;
    }

    // Закрытый набор причин: метка метрики не зависит от текста исключения.
    private enum Reason {
        RECEIPT_PUBLISH_FAILED("receipt_publish_failed"),
        EVENT_REJECTED("event_rejected"),
        RECEIVE_UNAVAILABLE("receive_unavailable");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String getLabel() {
            return label;
        }
    }
}
