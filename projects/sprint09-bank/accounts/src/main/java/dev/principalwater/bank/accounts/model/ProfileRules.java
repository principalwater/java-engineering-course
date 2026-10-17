package dev.principalwater.bank.accounts.model;

import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.time.LocalDate;
import java.util.regex.Pattern;

public final class ProfileRules {
    private static final int ADULT_AGE_YEARS = 18;
    private static final int MAX_NAME_LENGTH = 160;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ProfileRules() {}

    public static String validate(String name, LocalDate birthDate, LocalDate today) {
        String normalized = name == null ? "" : WHITESPACE.matcher(name.strip()).replaceAll(" ");
        if (normalized.length() > MAX_NAME_LENGTH
                || normalized.indexOf(' ') <= 0
                || normalized.codePoints().anyMatch(Character::isISOControl)
                || birthDate == null
                || birthDate.isAfter(today)
                || !birthDate.plusYears(ADULT_AGE_YEARS).isBefore(today)) {
            throw new BankException(BankError.INVALID_PROFILE);
        }
        return normalized;
    }
}
