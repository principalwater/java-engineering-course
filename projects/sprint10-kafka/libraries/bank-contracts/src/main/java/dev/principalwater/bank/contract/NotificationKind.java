package dev.principalwater.bank.contract;

public enum NotificationKind {
    PROFILE_UPDATED(NotificationSource.ACCOUNTS),
    DEPOSIT_SUCCEEDED(NotificationSource.CASH),
    DEPOSIT_REJECTED(NotificationSource.CASH),
    WITHDRAW_SUCCEEDED(NotificationSource.CASH),
    WITHDRAW_REJECTED(NotificationSource.CASH),
    TRANSFER_SUCCEEDED(NotificationSource.TRANSFER),
    TRANSFER_REJECTED(NotificationSource.TRANSFER);
    private final NotificationSource source;

    NotificationKind(NotificationSource source) {
        this.source = source;
    }

    public NotificationSource getSource() {
        return source;
    }
}
