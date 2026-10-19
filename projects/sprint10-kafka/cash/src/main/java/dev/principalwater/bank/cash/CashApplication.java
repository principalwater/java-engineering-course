package dev.principalwater.bank.cash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = {"dev.principalwater.bank.cash", "dev.principalwater.bank.runtime"})
public class CashApplication {
    public static void main(String[] args) {
        SpringApplication.run(CashApplication.class, args);
    }
}
