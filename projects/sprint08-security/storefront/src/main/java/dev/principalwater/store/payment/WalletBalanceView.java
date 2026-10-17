package dev.principalwater.store.payment;

import dev.principalwater.store.util.Money;

public record WalletBalanceView(Status status, String balanceMinor) {
    public enum Status { AVAILABLE, UNAVAILABLE }
    public static WalletBalanceView available(long minor) {
        return new WalletBalanceView(Status.AVAILABLE, Long.toString(minor));
    }
    public static WalletBalanceView unavailable() { return new WalletBalanceView(Status.UNAVAILABLE, null); }
    public boolean getAvailable() { return status == Status.AVAILABLE; }
    public String getBalanceMinor() { return balanceMinor; }
    public String getBalanceLabel() { return getAvailable() ? Money.label(Long.parseLong(balanceMinor)) : "Недоступен"; }
}
