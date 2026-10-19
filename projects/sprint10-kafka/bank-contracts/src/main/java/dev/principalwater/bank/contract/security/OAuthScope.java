package dev.principalwater.bank.contract.security;

import java.util.Arrays;

public enum OAuthScope {
    OPENID("openid"),
    PROFILE("profile"),
    ACCOUNTS_READ("accounts.read"),
    ACCOUNTS_WRITE("accounts.write"),
    ACCOUNTS_CASH("accounts.cash"),
    ACCOUNTS_TRANSFER("accounts.transfer"),
    ACCOUNTS_RESOLVE("accounts.resolve"),
    CASH_WRITE("cash.write"),
    TRANSFER_WRITE("transfer.write"),
    NOTIFICATIONS_WRITE("notifications.write"),
    ANALYTICS_READ("analytics.read"),
    ANALYTICS_EVENTS("analytics.events");

    private final String value;

    OAuthScope(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static String[] toStrings(OAuthScope... scopes) {
        return Arrays.stream(scopes).map(OAuthScope::getValue).toArray(String[]::new);
    }
}
