package dev.principalwater.bank.contract;

import java.time.LocalDate;
import java.util.UUID;

public record AccountView(UUID accountId, String login, String fullName, LocalDate birthDate,
                          long balanceMinor, long version, boolean profileComplete) { }
