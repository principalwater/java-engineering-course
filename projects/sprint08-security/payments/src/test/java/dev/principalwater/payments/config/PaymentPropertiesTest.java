package dev.principalwater.payments.config;

import dev.principalwater.payments.model.PaymentRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentPropertiesTest {
    @Test
    void openingBalanceAcceptsTheLedgerMaximum() {
        assertEquals(PaymentRules.MAX_AMOUNT_MINOR,
                new PaymentProperties(PaymentRules.MAX_AMOUNT_MINOR, 2).getOpeningBalanceMinor());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, PaymentRules.MAX_AMOUNT_MINOR + 1})
    void openingFundsRejectValuesOutsideTheLedgerRange(long opening) {
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(opening, 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    void ioWorkersRejectValuesOutsideTheBoundedRange(int workers) {
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(1000, workers));
    }
}
