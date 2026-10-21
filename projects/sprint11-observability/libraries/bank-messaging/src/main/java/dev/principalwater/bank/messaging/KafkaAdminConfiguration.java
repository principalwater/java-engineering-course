package dev.principalwater.bank.messaging;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaAdminConfiguration {
    // clusterId нужен для наблюдаемости: на metadata выделяется отдельный короткий бюджет.
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    KafkaAdmin bankKafkaAdmin(
            KafkaProperties properties,
            @Value("${bank.kafka.user}") String user,
            @Value("${bank.kafka.password}") String password) {
        // Boot не знает о JAAS, добавленном только в собственные producer/consumer factories.
        Map<String, Object> settings =
                KafkaClientProperties.secured(
                        properties.buildAdminProperties(null), user, password);
        settings.putIfAbsent(
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Math.toIntExact(METADATA_TIMEOUT.toMillis()));
        settings.putIfAbsent(
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                Math.toIntExact(METADATA_TIMEOUT.toMillis()));
        KafkaAdmin admin = new KafkaAdmin(settings);
        // Топики и ACL принадлежат Strimzi; клиент только читает идентификатор кластера.
        admin.setAutoCreate(false);
        admin.setOperationTimeout(Math.toIntExact(METADATA_TIMEOUT.toSeconds()));
        return admin;
    }
}
