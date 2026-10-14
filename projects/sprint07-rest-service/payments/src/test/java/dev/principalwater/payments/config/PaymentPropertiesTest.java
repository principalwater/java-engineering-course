package dev.principalwater.payments.config;

import dev.principalwater.payments.model.PaymentRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PaymentPropertiesTest {
    private static final String SECRET = "payment-service-property-token-1234567890";

    @Test
    void configuredIdentityAcceptsOnlyItsSecretAndDoesNotPrintCredentials() {
        var properties = new PaymentProperties(SECRET + "\n", PaymentRules.MAX_AMOUNT_MINOR, 2);
        assertTrue(properties.accepts(SECRET));
        assertFalse(properties.accepts(SECRET + "x"));
        assertFalse(properties.accepts(null));
        assertFalse(properties.toString().contains(SECRET));
        assertEquals(PaymentRules.MAX_AMOUNT_MINOR, properties.getOpeningBalanceMinor());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "service identity must not contain whitespace"})
    void missingOrUnsafeCredentialsFailClosedBeforeStartup(String token) {
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(token, 1000, 2));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, PaymentRules.MAX_AMOUNT_MINOR + 1})
    void openingFundsRejectValuesOutsideTheLedgerRange(long opening) {
        assertThrows(IllegalArgumentException.class, () -> new PaymentProperties(SECRET, opening, 2));
    }
}
