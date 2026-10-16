package dev.principalwater.store.config;

import dev.principalwater.store.TestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryPropertiesTest extends TestBase {
    @Test
    void linksAreBuiltOnlyFromAFixedHttpsBaseOrALoopbackDevelopmentBase() {
        assertEquals("https://shop.example.test/account/recovery/reset?token=T",
                new RecoveryProperties(true, "https://shop.example.test//", "").resetLink("T"));
        assertEquals("http://127.0.0.1:8088/account/recovery/reset?token=T",
                new RecoveryProperties(true, "http://127.0.0.1:8088", "").resetLink("T"));
        assertEquals("", new RecoveryProperties(true, "", "").publicBaseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example.test", "https://user@shop.example.test", "https://shop.example.test/?a=b",
            "https://shop.example.test/#fragment", "ftp://shop.example.test", "/relative", "https:///no-host", "https://bad host"})
    void everyOtherBaseIsRejectedAtStartup(String base) {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryProperties(true, base, ""));
    }

    @Test
    void theSenderMustBeAValidAddress() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryProperties(true, "", "not-an-address"));
    }
}
