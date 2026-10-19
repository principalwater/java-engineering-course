package dev.principalwater.bank.contract;

import java.util.UUID;

// Владелец ответа позволяет UI отклонить запоздалую загрузку после смены сессии.
public record NotificationInbox(UUID accountId, Page<NotificationView> notifications) {}
