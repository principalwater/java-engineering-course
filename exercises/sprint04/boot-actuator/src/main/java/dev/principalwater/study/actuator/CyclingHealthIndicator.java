package dev.principalwater.study.actuator;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("cycleCheck")
@Profile("probes")
public class CyclingHealthIndicator implements HealthIndicator {
    private final AtomicInteger samples = new AtomicInteger();

    @Override
    public Health health() {
        // Учебный индикатор показывает агрегацию статусов, реальный сервис так не проверяют.
        boolean healthy = samples.getAndIncrement() % 2 == 0;
        return (healthy ? Health.up() : Health.down())
                .withDetail("mode", "учебное чередование").build();
    }
}
