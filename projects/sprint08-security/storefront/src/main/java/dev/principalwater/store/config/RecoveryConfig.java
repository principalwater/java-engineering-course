package dev.principalwater.store.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
public class RecoveryConfig {
    private static final int MAIL_WORKERS = 2;
    private static final int MAIL_QUEUE_CAPACITY = 32;

    // Блокирующий SMTP не делит пул с BCrypt: медленная почта не должна останавливать вход в аккаунт.
    @Bean(destroyMethod = "dispose")
    public Scheduler recoveryMailScheduler() {
        return Schedulers.newBoundedElastic(MAIL_WORKERS, MAIL_QUEUE_CAPACITY, "store-mail");
    }
}
