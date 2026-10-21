package dev.principalwater.bank.transfer;

import dev.principalwater.bank.messaging.KafkaMessagingConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Import(KafkaMessagingConfiguration.class)
@SpringBootApplication(
        scanBasePackages = {"dev.principalwater.bank.transfer", "dev.principalwater.bank.runtime"})
public class TransferApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransferApplication.class, args);
    }
}
