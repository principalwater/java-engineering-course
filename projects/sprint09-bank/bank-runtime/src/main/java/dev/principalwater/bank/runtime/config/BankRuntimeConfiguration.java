package dev.principalwater.bank.runtime.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BankRuntimeConfiguration {
    @Bean
    Clock bankClock() { return Clock.systemUTC(); }
}
