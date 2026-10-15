package dev.principalwater.payments.config;

import dev.principalwater.payments.model.PaymentRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payments")
public final class PaymentProperties {
    private static final int MIN_TOKEN_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 256;
    private static final int MAX_IO_WORKERS = 8;
    private final byte[] serviceTokenDigest;
    private final long openingBalanceMinor;
    private final int ioWorkers;

    public PaymentProperties(String serviceToken, @DefaultValue("1000000000") long openingBalanceMinor,
                             @DefaultValue("2") int ioWorkers) {
        if (serviceToken == null || !validToken(serviceToken.strip())) {
            throw new IllegalArgumentException("payments.service-token must contain " + MIN_TOKEN_LENGTH + " to "
                    + MAX_TOKEN_LENGTH + " URL-safe characters");
        }
        if (openingBalanceMinor < 1 || openingBalanceMinor > PaymentRules.MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Opening balance must be between 1 and " + PaymentRules.MAX_AMOUNT_MINOR + " minor units");
        }
        if (ioWorkers < 1 || ioWorkers > MAX_IO_WORKERS) {
            throw new IllegalArgumentException("Payment HTTP worker count must be between 1 and " + MAX_IO_WORKERS);
        }
        serviceTokenDigest = digest(serviceToken.strip());
        this.openingBalanceMinor = openingBalanceMinor;
        this.ioWorkers = ioWorkers;
    }

    public boolean accepts(String token) {
        return token != null && validToken(token) && MessageDigest.isEqual(serviceTokenDigest, digest(token));
    }

    public long getOpeningBalanceMinor() { return openingBalanceMinor; }
    public int getIoWorkers() { return ioWorkers; }

    private static boolean validToken(String token) {
        return token.length() >= MIN_TOKEN_LENGTH && token.length() <= MAX_TOKEN_LENGTH
                && token.chars().allMatch(character -> character >= 'a' && character <= 'z'
                        || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9'
                        || character == '-' || character == '_');
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
