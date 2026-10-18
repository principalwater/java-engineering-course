package dev.principalwater.bank.accounts.model;

import dev.principalwater.bank.contract.AccountView;
import java.time.LocalDate;
import java.util.UUID;

public record Account(
        UUID id,
        String issuer,
        String subject,
        String login,
        String fullName,
        LocalDate birthDate,
        long balanceMinor,
        long version) {
    public boolean profileComplete() {
        return birthDate != null && !fullName.isBlank();
    }

    public AccountView view() {
        return new AccountView(
                id, login, fullName, birthDate, balanceMinor, version, profileComplete());
    }
}
