package dev.principalwater.bank.notifications.service;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.messaging.MessageCodec;
import dev.principalwater.bank.messaging.MessagePersistence;
import dev.principalwater.bank.messaging.MessageRejected;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {
    private final MessageCodec codec;
    private final NotificationService notifications;

    public NotificationListener(MessageCodec codec, NotificationService notifications) {
        this.codec = codec;
        this.notifications = notifications;
    }

    @KafkaListener(
            topics = {
                BankTopics.ACCOUNTS_NOTIFICATIONS,
                BankTopics.CASH_NOTIFICATIONS,
                BankTopics.TRANSFER_NOTIFICATIONS
            },
            groupId = BankTopics.NOTIFICATIONS_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        BankEvent event = codec.decode(record.value(), BankEvent.class);
        if (!event.ownerId().toString().equals(record.key())) throw new MessageRejected();
        // Отправителя определяет топик с ACL; заголовки и содержимое не подтверждают личность
        // продюсера.
        MessagePersistence.store(
                () -> notifications.receive(NotificationSource.fromTopic(record.topic()), event));
    }
}
