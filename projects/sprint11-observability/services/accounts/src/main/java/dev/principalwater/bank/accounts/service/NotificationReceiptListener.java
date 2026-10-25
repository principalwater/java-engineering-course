package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.messaging.MessageCodec;
import dev.principalwater.bank.messaging.MessagePersistence;
import dev.principalwater.bank.messaging.MessageRejected;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationReceiptListener {
    private final MessageCodec codec;
    private final AccountNotifications notifications;

    public NotificationReceiptListener(MessageCodec codec, AccountNotifications notifications) {
        this.codec = codec;
        this.notifications = notifications;
    }

    @KafkaListener(
            topics = BankTopics.NOTIFICATION_RECEIPTS,
            groupId = BankTopics.ACCOUNTS_NOTIFICATIONS_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        NotificationReceipt receipt = codec.decode(record.value(), NotificationReceipt.class);
        if (!receipt.event().ownerId().toString().equals(record.key())) throw new MessageRejected();
        MessagePersistence.store(() -> notifications.accept(receipt));
    }
}
