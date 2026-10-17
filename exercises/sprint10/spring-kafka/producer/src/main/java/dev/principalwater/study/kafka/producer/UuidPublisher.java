package dev.principalwater.study.kafka.producer;

import dev.principalwater.study.kafka.api.Topics;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("uuid")
class UuidPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(UuidPublisher.class);
    private final KafkaTemplate<String, String> template;

    UuidPublisher(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @Scheduled(fixedDelayString = "PT1s")
    void publish() {
        String data = UUID.randomUUID().toString();
        try {
            // Ожидание подтверждения ограничивает число незавершённых отправок одним сообщением.
            var metadata = template.send(Topics.DEMO, data).get().getRecordMetadata();
            LOG.info("UUID_SENT id={} partition={} offset={}", data, metadata.partition(), metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            LOG.error("Kafka send failed category={}", exception.getCause().getClass().getSimpleName());
        }
    }
}
