package dev.principalwater.store.analytics;

public record DeliveryResult(Status status, int deliveredOrders) {
    public enum Status { DELIVERED, IN_PROGRESS, UNAVAILABLE }

    public static DeliveryResult delivered(int count) { return new DeliveryResult(Status.DELIVERED, count); }
    public static DeliveryResult inProgress() { return new DeliveryResult(Status.IN_PROGRESS, 0); }
    public static DeliveryResult unavailable() { return new DeliveryResult(Status.UNAVAILABLE, 0); }
}
