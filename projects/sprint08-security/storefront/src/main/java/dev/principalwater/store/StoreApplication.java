package dev.principalwater.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;

// Автонастройка сессий Boot привязала бы их к первому же Redis, то есть к кэшу каталога с вытеснением записей.
// Общие сессии включает только профиль shared-sessions (SharedSessionConfig) на отдельном Redis.
@SpringBootApplication(exclude = SessionAutoConfiguration.class)
public class StoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
