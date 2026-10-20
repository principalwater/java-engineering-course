package dev.principalwater.bank.accounts;

import dev.principalwater.bank.messaging.KafkaMessagingConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Import(KafkaMessagingConfiguration.class)
@SpringBootApplication(
        scanBasePackages = {"dev.principalwater.bank.accounts", "dev.principalwater.bank.runtime"})
public class AccountsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountsApplication.class, args);
    }
}
