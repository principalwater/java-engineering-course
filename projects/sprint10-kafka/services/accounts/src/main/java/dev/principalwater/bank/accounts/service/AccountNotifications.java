package dev.principalwater.bank.accounts.service;

import dev.principalwater.bank.accounts.dao.NotificationInboxDao;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.NotificationInbox;
import dev.principalwater.bank.contract.NotificationKind;
import dev.principalwater.bank.contract.NotificationRead;
import dev.principalwater.bank.contract.NotificationReadResult;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.messaging.MessageRejected;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountNotifications {
    private final ProfileService profiles;
    private final NotificationInboxDao notifications;
    private final KafkaOutbox outbox;
    private final Clock clock;
    private final boolean analyticsEnabled;

    public AccountNotifications(
            ProfileService profiles,
            NotificationInboxDao notifications,
            KafkaOutbox outbox,
            Clock clock,
            @Value("${bank.analytics.enabled:false}") boolean analyticsEnabled) {
        this.profiles = profiles;
        this.notifications = notifications;
        this.outbox = outbox;
        this.clock = clock;
        this.analyticsEnabled = analyticsEnabled;
    }

    public NotificationInbox inbox(Actor actor, Long before, int limit) {
        UUID owner = profiles.own(actor).accountId();
        return new NotificationInbox(owner, notifications.inbox(owner, before, limit));
    }

    @Transactional
    public void accept(NotificationReceipt receipt) {
        try {
            if (NotificationKind.valueOf(receipt.event().type()).getSource() != receipt.source())
                throw new MessageRejected();
        } catch (IllegalArgumentException failure) {
            throw new MessageRejected();
        }
        if (notifications.accept(receipt))
            analytics(
                    "NOTIFICATION_AVAILABLE",
                    receipt.source(),
                    receipt.event().eventId(),
                    receipt.event().ownerId(),
                    clock.instant());
    }

    @Transactional
    public NotificationReadResult read(Actor actor, NotificationRead request) {
        UUID owner = profiles.own(actor).accountId();
        NotificationInboxDao.Read read =
                notifications.read(
                        owner,
                        request.source(),
                        request.eventId(),
                        clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (read.first())
            analytics(
                    "NOTIFICATION_READ",
                    request.source(),
                    request.eventId(),
                    owner,
                    read.view().readAt());
        return new NotificationReadResult(owner, read.view());
    }

    private void analytics(
            String type, NotificationSource source, UUID eventId, UUID owner, Instant now) {
        if (!analyticsEnabled) return;
        UUID id =
                UUID.nameUUIDFromBytes(
                        (type + ":" + owner + ":" + source + ":" + eventId)
                                .getBytes(StandardCharsets.UTF_8));
        // Нефинансовый факт не утверждает баланс; нули являются частью отдельного типа события.
        outbox.enqueue(
                BankTopics.ACCOUNTS_ANALYTICS,
                owner.toString(),
                id.toString(),
                new BankEvent(id, owner, type, 0, 0, now));
    }
}
