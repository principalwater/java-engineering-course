package dev.principalwater.bank.cash;

import dev.principalwater.bank.messaging.KafkaMessagingConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Import(KafkaMessagingConfiguration.class)
@SpringBootApplication(
        scanBasePackages = {"dev.principalwater.bank.cash", "dev.principalwater.bank.runtime"})
public class CashApplication {
    public static void main(String[] args) {
        SpringApplication.run(CashApplication.class, args);
    }
}
