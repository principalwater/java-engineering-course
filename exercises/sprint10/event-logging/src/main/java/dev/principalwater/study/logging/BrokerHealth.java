package dev.principalwater.study.logging;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
class BrokerHealth {
    @Bean(destroyMethod = "close")
    Admin healthAdmin(KafkaAdmin kafkaAdmin) {
        return Admin.create(kafkaAdmin.getConfigurationProperties());
    }

    @Bean
    HealthIndicator broker(Admin healthAdmin) {
        return () -> {
            try {
                healthAdmin.describeCluster().nodes().get(1, TimeUnit.SECONDS);
                return Health.up().build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Health.down().build();
            } catch (Exception exception) {
                // Категория достаточна для диагностики пробы; exception/сетевые настройки наружу не выводятся.
                return Health.down().withDetail("category", exception.getClass().getSimpleName()).build();
            }
        };
    }
}
