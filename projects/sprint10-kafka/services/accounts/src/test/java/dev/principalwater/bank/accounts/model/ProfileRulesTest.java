package dev.principalwater.bank.accounts.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.principalwater.bank.runtime.error.BankException;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileRulesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @ParameterizedTest(name = "{0}")
    @MethodSource("ageBoundaries")
    void requiresAgeStrictlyOverEighteenRatherThanAtLeastEighteen(
            String scenario, LocalDate birthDate, LocalDate today, boolean accepted) {
        if (accepted) {
            assertEquals(
                    "Alice Example", ProfileRules.validate(" Alice   Example ", birthDate, today));
        } else {
            assertThrows(
                    BankException.class,
                    () -> ProfileRules.validate("Alice Example", birthDate, today));
        }
    }

    private static Stream<Arguments> ageBoundaries() {
        return Stream.of(
                Arguments.of("exact eighteenth birthday", TODAY.minusYears(18), TODAY, false),
                Arguments.of(
                        "day after eighteenth birthday",
                        TODAY.minusYears(18).minusDays(1),
                        TODAY,
                        true),
                Arguments.of("future birthday", TODAY.plusDays(1), TODAY, false),
                Arguments.of(
                        "February birthday before leap day",
                        LocalDate.of(2006, 2, 28),
                        LocalDate.of(2024, 2, 29),
                        true),
                Arguments.of(
                        "leap-born eighteenth birthday",
                        LocalDate.of(2008, 2, 29),
                        LocalDate.of(2026, 2, 28),
                        false),
                Arguments.of(
                        "day after leap-born birthday",
                        LocalDate.of(2008, 2, 29),
                        LocalDate.of(2026, 3, 1),
                        true),
                Arguments.of("maximum future date", LocalDate.MAX, TODAY, false));
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
