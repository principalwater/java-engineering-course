package dev.principalwater.store.model;

public enum OrderStatus {
    LEGACY_UNPAID("Без подтверждения оплаты"), PAID("Оплачен");
    private final String label;
    OrderStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
