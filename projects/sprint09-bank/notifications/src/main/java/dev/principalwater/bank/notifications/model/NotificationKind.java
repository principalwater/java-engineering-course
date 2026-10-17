package dev.principalwater.bank.notifications.model;

import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;

public enum NotificationKind {
    PROFILE_UPDATED,
    DEPOSIT_SUCCEEDED,
    DEPOSIT_REJECTED,
    WITHDRAW_SUCCEEDED,
    WITHDRAW_REJECTED,
    TRANSFER_SUCCEEDED,
    TRANSFER_REJECTED;

    public boolean accepts(String caller) {
        String expected =
                switch (this) {
                    case PROFILE_UPDATED -> "accounts-service";
                    case DEPOSIT_SUCCEEDED,
                            DEPOSIT_REJECTED,
                            WITHDRAW_SUCCEEDED,
                            WITHDRAW_REJECTED ->
                            "cash-service";
                    case TRANSFER_SUCCEEDED, TRANSFER_REJECTED -> "transfer-service";
                };
        return expected.equals(caller);
    }

    public static NotificationKind parse(String type) {
        for (NotificationKind kind : values()) {
            if (kind.name().equals(type)) {
                return kind;
            }
        }
        throw new BankException(BankError.INVALID_REQUEST);
    }
}
