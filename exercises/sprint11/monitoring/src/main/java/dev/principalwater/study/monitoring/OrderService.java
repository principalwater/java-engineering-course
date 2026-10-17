package dev.principalwater.study.monitoring;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
class OrderService {
    private static final int SAVE_DELAY_LIMIT_MS = 1000;
    private static final int READ_DELAY_LIMIT_MS = 200;
    private final OrderRepository orders;
    private final Counter[] created = new Counter[OrderType.values().length];

    OrderService(OrderRepository orders, MeterRegistry registry) {
        this.orders = orders;
        for (var type : OrderType.values()) {
            created[type.ordinal()] = registry.counter("orders", "type", type.name());
        }
    }

    Order save(String username, OrderType type) {
        delay(SAVE_DELAY_LIMIT_MS);
        // Транзакция repository.save уже завершена: не считаем откатившуюся запись успешной.
        var saved = orders.save(new Order(username, type));
        created[type.ordinal()].increment();
        return saved;
    }

    List<Order> findAll() {
        delay(READ_DELAY_LIMIT_MS);
        // shortcut: выдача всех заказов годится для конечной учебной нагрузки; для долгой работы нужна пагинация.
        return orders.findAll(Sort.by("id"));
    }

    private static void delay(int limitMillis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(limitMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Order operation interrupted", interrupted);
        }
    }
}
