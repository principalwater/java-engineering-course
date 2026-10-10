package dev.principalwater.study.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.redis.core.RedisKeyValueAdapter;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@SpringBootApplication
@EnableRedisRepositories(enableKeyspaceEvents = RedisKeyValueAdapter.EnableKeyspaceEvents.ON_STARTUP)
public class RedisLabApplication {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(RedisLabApplication.class, args)) {
            // После ApplicationRunner контекст закрывается, освобождая подключения Lettuce.
        }
    }
}
