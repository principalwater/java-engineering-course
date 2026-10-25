package dev.principalwater.bank.messaging;

import java.util.UUID;

// Внутрипроцессный сигнал после сохранения повтора; payload не журналируется и не отправляется в
// метрики.
public record OutboxDeliveryFailure(UUID outboxId, String topic, String payload) {}
