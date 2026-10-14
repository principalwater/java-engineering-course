package dev.principalwater.store.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("store.payments")
public record PaymentProperties(@DefaultValue("http://localhost:18097") URI url,
                                @DefaultValue("") String serviceToken,
                                @DefaultValue("1s") Duration balanceTimeout,
                                @DefaultValue("5s") Duration debitTimeout) {
    public PaymentProperties {
        if (url == null || url.getHost() == null || !java.util.Set.of("http", "https").contains(url.getScheme())
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("Payments URL must be an HTTP(S) service origin without credentials");
        }
        if (balanceTimeout == null || debitTimeout == null || balanceTimeout.isNegative() || balanceTimeout.isZero()
                || debitTimeout.compareTo(balanceTimeout) < 0 || debitTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Payment timeouts must be positive, ordered and at most 10 seconds");
        }
    }
    @Override public String toString() { return "PaymentProperties[url=" + url + "]"; }
}
