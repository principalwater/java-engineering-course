package dev.principalwater.bank.notifications.model;

import dev.principalwater.bank.contract.security.BankTokenProfile;
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
                    case PROFILE_UPDATED -> BankTokenProfile.ACCOUNTS_CLIENT_ID;
                    case DEPOSIT_SUCCEEDED,
                            DEPOSIT_REJECTED,
                            WITHDRAW_SUCCEEDED,
                            WITHDRAW_REJECTED ->
                            BankTokenProfile.CASH_CLIENT_ID;
                    case TRANSFER_SUCCEEDED, TRANSFER_REJECTED ->
                            BankTokenProfile.TRANSFER_CLIENT_ID;
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
