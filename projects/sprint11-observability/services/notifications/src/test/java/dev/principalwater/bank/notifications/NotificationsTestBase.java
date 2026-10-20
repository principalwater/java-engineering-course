package dev.principalwater.bank.notifications;

import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.testing.KafkaFixture;
import dev.principalwater.bank.testing.PostgresTestBase;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.config.import=classpath:bank-common.properties,classpath:bank-kafka.properties",
            "spring.kafka.security.protocol=PLAINTEXT",
            // Один контекст на модуль: факт отказа пишется только при сбое публикации receipt.
            "bank.analytics.enabled=true",
            "stubrunner.cloud.enabled=false"
        })
public abstract class NotificationsTestBase extends PostgresTestBase {
    protected static final String[] TOPICS;

    static {
        List<String> topics = new ArrayList<>();
        for (NotificationSource source : NotificationSource.values()) {
            topics.add(source.getTopic());
            topics.add(source.getTopic() + BankTopics.DLT_SUFFIX);
        }
        topics.add(BankTopics.NOTIFICATION_RECEIPTS);
        TOPICS = topics.toArray(String[]::new);
        KafkaFixture.topics(TOPICS);
    }

    @Autowired protected KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KafkaFixture::bootstrapServers);
    }

    @Override
    protected void resetDatabase() {
        stopConsumers();
        KafkaFixture.clear(TOPICS);
        jdbc.execute("TRUNCATE bank_notification,bank_kafka_outbox RESTART IDENTITY");
    }

    protected void startConsumers() {
        listeners.getListenerContainers().forEach(container -> container.start());
    }

    protected void stopConsumers() {
        listeners.getListenerContainers().forEach(container -> container.stop());
    }
}
