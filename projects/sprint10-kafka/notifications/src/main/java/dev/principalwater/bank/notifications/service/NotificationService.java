package dev.principalwater.bank.notifications.service;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.NotificationKind;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.messaging.MessageRejected;
import dev.principalwater.bank.notifications.dao.NotificationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationDao notifications;
    private final KafkaOutbox outbox;
    private final TransactionTemplate transaction;

    public NotificationService(
            NotificationDao notifications,
            KafkaOutbox outbox,
            PlatformTransactionManager transactions) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactions);
    }

    public void receive(NotificationSource source, BankEvent event) {
        NotificationKind kind;
        try {
            kind = NotificationKind.valueOf(event.type());
        } catch (IllegalArgumentException failure) {
            throw new MessageRejected();
        }
        if (kind.getSource() != source) throw new MessageRejected();
        Boolean inserted =
                transaction.execute(
                        status -> {
                            boolean fresh = notifications.receive(source.getCaller(), event);
                            // Receipt и его исходящий факт фиксируются вместе: crash не теряет
                            // доставку в кабинет.
                            outbox.enqueue(
                                    BankTopics.NOTIFICATION_RECEIPTS,
                                    event.ownerId().toString(),
                                    source.identity(event.eventId()),
                                    new NotificationReceipt(source, event));
                            return fresh;
                        });
        if (Boolean.TRUE.equals(inserted)) LOG.info("Notification action={}", kind);
    }
}
