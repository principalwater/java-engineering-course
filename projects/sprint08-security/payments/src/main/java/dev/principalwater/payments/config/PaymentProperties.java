package dev.principalwater.payments.config;

import dev.principalwater.payments.model.PaymentRules;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payments")
public final class PaymentProperties {
    private static final int MAX_IO_WORKERS = 8;
    private final long openingBalanceMinor;
    private final int ioWorkers;

    public PaymentProperties(@DefaultValue("1000000000") long openingBalanceMinor, @DefaultValue("2") int ioWorkers) {
        if (openingBalanceMinor < 1 || openingBalanceMinor > PaymentRules.MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("Opening balance must be between 1 and " + PaymentRules.MAX_AMOUNT_MINOR + " minor units");
        }
        if (ioWorkers < 1 || ioWorkers > MAX_IO_WORKERS) {
            throw new IllegalArgumentException("Payment HTTP worker count must be between 1 and " + MAX_IO_WORKERS);
        }
        this.openingBalanceMinor = openingBalanceMinor;
        this.ioWorkers = ioWorkers;
    }

    public long getOpeningBalanceMinor() { return openingBalanceMinor; }
    public int getIoWorkers() { return ioWorkers; }
}
