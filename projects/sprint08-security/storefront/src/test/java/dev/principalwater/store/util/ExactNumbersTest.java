package dev.principalwater.store.util;

import dev.principalwater.store.TestBase;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExactNumbersTest extends TestBase {
    @ParameterizedTest
    @MethodSource("integralValues")
    void acceptsIntegralDriverValuesWithoutChangingThem(Number value, long expected) {
        assertEquals(expected, ExactNumbers.longValueExact(value));
    }

    @ParameterizedTest
    @MethodSource("inexactValues")
    void rejectsOverflowAndFractionalValuesInsteadOfSilentlyTruncatingThem(Number value) {
        assertThrows(ArithmeticException.class, () -> ExactNumbers.longValueExact(value));
    }

    @ParameterizedTest
    @MethodSource("floatingValues")
    void rejectsFloatingPointTypesEvenWhenTheirDisplayedValueIsIntegral(Number value) {
        assertThrows(IllegalArgumentException.class, () -> ExactNumbers.longValueExact(value));
    }

    static Stream<Arguments> integralValues() {
        return Stream.of(Arguments.of(BigDecimal.valueOf(Long.MAX_VALUE), Long.MAX_VALUE),
                Arguments.of(BigInteger.valueOf(Long.MIN_VALUE), Long.MIN_VALUE), Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of(Integer.MAX_VALUE, (long) Integer.MAX_VALUE), Arguments.of((short) -1, -1L), Arguments.of((byte) 0, 0L));
    }

    static Stream<Number> inexactValues() {
        return Stream.of(BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE),
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE), new BigDecimal("1.5"));
    }

    static Stream<Number> floatingValues() {
        return Stream.of(1.0f, 1.0d);
    }
}
