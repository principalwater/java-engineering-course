package dev.principalwater.store.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {
    @Bean
    public Clock storeClock() {
        return Clock.systemUTC();
    }
}
