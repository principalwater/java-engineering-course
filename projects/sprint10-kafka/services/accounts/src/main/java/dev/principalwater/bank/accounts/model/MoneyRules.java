package dev.principalwater.bank.accounts.model;

import dev.principalwater.bank.contract.BankLimits;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.runtime.error.BankError;

public final class MoneyRules {
    private MoneyRules() {}

    public static BankError rejection(
            Account source, Account recipient, InternalOperation command) {
        if (!source.profileComplete()) {
            return BankError.PROFILE_REQUIRED;
        }
        long amount = command.amountMinor();
        if (command.type() == OperationType.DEPOSIT) {
            return amount > BankLimits.MAX_BALANCE_MINOR - source.balanceMinor()
                    ? BankError.BALANCE_LIMIT
                    : null;
        }
        if (command.type() == OperationType.TRANSFER) {
            if (source.id().equals(command.recipientId())) {
                return BankError.SELF_TRANSFER;
            }
            if (recipient == null || !recipient.profileComplete()) {
                return BankError.RECIPIENT_NOT_FOUND;
            }
            if (amount > BankLimits.MAX_BALANCE_MINOR - recipient.balanceMinor()) {
                return BankError.BALANCE_LIMIT;
            }
        }
        return amount > source.balanceMinor() ? BankError.INSUFFICIENT_FUNDS : null;
    }
}
