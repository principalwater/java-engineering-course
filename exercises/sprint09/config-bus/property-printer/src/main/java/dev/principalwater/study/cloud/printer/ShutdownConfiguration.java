package dev.principalwater.study.cloud.printer;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.cloud.bus.ServiceMatcher;
import org.springframework.cloud.bus.event.ShutdownListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ShutdownConfiguration {
    @Bean
    ShutdownListener shutdownListener(ServiceMatcher matcher) {
        return new ShutdownListener(matcher) {
            private final AtomicBoolean closing = new AtomicBoolean();

            @Override
            protected int shutdown() {
                // Синхронное закрытие ждёт собственный HTTP/AMQP callback и срывает graceful shutdown.
                if (closing.compareAndSet(false, true)) {
                    Thread.ofPlatform().name("bus-shutdown").start(super::shutdown);
                }
                return 0;
            }
        };
    }
}
