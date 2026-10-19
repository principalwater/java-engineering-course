package dev.principalwater.bank.notifications.contract;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.notifications.NotificationsTestBase;
import dev.principalwater.bank.notifications.service.NotificationService;
import dev.principalwater.bank.testing.KafkaContractConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(KafkaContractConfiguration.class)
public abstract class NotificationContractBase extends NotificationsTestBase {
    @Autowired private NotificationService notifications;
    @Autowired private KafkaOutbox outbox;

    public void publishNotification() {
        notifications.receive(
                NotificationSource.CASH,
                new BankEvent(
                        UUID.fromString("49a83c26-7e3d-4142-a1be-05d8f6088aaf"),
                        UUID.fromString("5808b517-d97b-4fdc-afc0-8f983fb28bc7"),
                        "DEPOSIT_SUCCEEDED",
                        1000,
                        1000,
                        Instant.parse("2026-10-17T12:00:00Z")));
        outbox.pendingIds().forEach(outbox::recover);
    }
}
