package dev.principalwater.bank.accounts.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.principalwater.bank.runtime.error.BankException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileRulesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Test
    void requiresAgeStrictlyOverEighteenRatherThanAtLeastEighteen() {
        assertThrows(
                BankException.class,
                () -> ProfileRules.validate("Alice Example", TODAY.minusYears(18), TODAY));
        assertEquals(
                "Alice Example",
                ProfileRules.validate(
                        " Alice   Example ", TODAY.minusYears(18).minusDays(1), TODAY));
        assertThrows(
                BankException.class,
                () -> ProfileRules.validate("Alice Example", TODAY.plusDays(1), TODAY));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Alice", "   ", "Alice\u0000 Example"})
    void requiresBothNamePartsWithoutControlCharacters(String name) {
        assertThrows(
                BankException.class,
                () -> ProfileRules.validate(name, TODAY.minusYears(25), TODAY));
    }
}
