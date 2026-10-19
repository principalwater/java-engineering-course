package dev.principalwater.bank.runtime.delivery;

public final class DeliveryPolicy {
    // Один размер ограничивает сканы и очередь восстановления.
    public static final int BATCH_SIZE = 16;
    // Lease длиннее сетевого ожидания; после сбоя команду подберёт следующий worker.
    public static final int LEASE_SECONDS = 30;
    // Пауза после неизвестного исхода предотвращает непрерывный цикл запросов.
    public static final int RETRY_DELAY_SECONDS = 5;

    private DeliveryPolicy() {}
}
