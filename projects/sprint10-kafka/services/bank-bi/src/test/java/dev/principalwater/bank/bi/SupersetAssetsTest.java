package dev.principalwater.bank.bi;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupersetAssetsTest {
    @ParameterizedTest
    @ValueSource(
            strings = {"user@host", "host/path", "host?query", "host#fragment", "host\nother", ""})
    void rejectsDatabaseUriInjection(String host) {
        assertThrows(IllegalArgumentException.class, () -> SupersetAssets.archive(host));
    }
}
