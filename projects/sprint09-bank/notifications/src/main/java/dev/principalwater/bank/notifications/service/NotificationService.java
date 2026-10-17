package dev.principalwater.bank.notifications.service;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.notifications.dao.NotificationDao;
import dev.principalwater.bank.notifications.model.NotificationKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationDao notifications;
    private final TransactionTemplate transaction;

    public NotificationService(
            NotificationDao notifications, PlatformTransactionManager transactions) {
        this.notifications = notifications;
        this.transaction = new TransactionTemplate(transactions);
    }

    public void receive(String caller, BankEvent event) {
        NotificationKind kind = NotificationKind.parse(event.type());
        if (!kind.accepts(caller)) {
            throw new AccessDeniedException("Caller cannot send this notification action");
        }
        Boolean inserted = transaction.execute(status -> notifications.receive(caller, event));
        // Best-effort после commit; receipt долговечен, в журнал попадает только enum действия.
        if (Boolean.TRUE.equals(inserted)) {
            LOG.info("Notification action={}", kind);
        }
    }
}
