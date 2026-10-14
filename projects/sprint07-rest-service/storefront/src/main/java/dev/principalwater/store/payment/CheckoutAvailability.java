package dev.principalwater.store.payment;

import dev.principalwater.store.util.Money;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckoutAvailability(Status status, String balanceMinor, String requiredMinor, String shortfallMinor, String checkoutKey) {
    public enum Status { AVAILABLE, INSUFFICIENT, UNAVAILABLE, PENDING }
    public static CheckoutAvailability from(WalletBalanceView wallet, long required, boolean pending, java.util.UUID key) {
        if (pending) { return new CheckoutAvailability(Status.PENDING, wallet.balanceMinor(), Long.toString(required), null, key.toString()); }
        if (!wallet.getAvailable()) { return new CheckoutAvailability(Status.UNAVAILABLE, null, Long.toString(required), null, key.toString()); }
        long balance = Long.parseLong(wallet.balanceMinor());
        return new CheckoutAvailability(balance >= required ? Status.AVAILABLE : Status.INSUFFICIENT,
                wallet.balanceMinor(), Long.toString(required), Long.toString(Math.max(0, required - balance)), key.toString());
    }
    @JsonProperty("status")
    public Status getStatus() { return status; }
    @JsonProperty("canBuy")
    public boolean getCanBuy() { return status == Status.AVAILABLE && Long.parseLong(requiredMinor) > 0; }
    @JsonProperty("pending")
    public boolean getPending() { return status == Status.PENDING; }
    @JsonProperty("message")
    public String getMessage() {
        return switch (status) {
            case AVAILABLE -> "Средств достаточно";
            case INSUFFICIENT -> "Недостаточно средств: не хватает " + Money.label(Long.parseLong(shortfallMinor));
            case UNAVAILABLE -> "Сервис платежей временно недоступен. Корзина сохранена.";
            case PENDING -> "Уточняем результат платежа. Повторная проверка не спишет деньги ещё раз.";
        };
    }
    @JsonProperty("checkoutKey")
    public String getCheckoutKey() { return checkoutKey; }
    public String getRequiredLabel() { return Money.label(Long.parseLong(requiredMinor)); }
    @JsonProperty("balanceLabel")
    public String getBalanceLabel() { return balanceMinor == null ? "Недоступен" : Money.label(Long.parseLong(balanceMinor)); }
}
