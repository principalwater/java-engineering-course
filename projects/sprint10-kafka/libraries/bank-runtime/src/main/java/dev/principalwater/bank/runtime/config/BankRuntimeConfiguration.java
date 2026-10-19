package dev.principalwater.bank.runtime.config;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BankRuntimeConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(BankRuntimeConfiguration.class);

    @Bean
    Clock bankClock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner sharedConfigurationRevision(
            @Value("${bank.config.revision:local}") String revision) {
        // Только публичная ревизия: значения подключений и секреты в журнал не попадают.
        return arguments -> LOG.info("Shared bank configuration revision: {}", revision);
    }
}
