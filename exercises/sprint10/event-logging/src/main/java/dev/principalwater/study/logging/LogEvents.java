package dev.principalwater.study.logging;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class LogEvents {
    private static final Logger LOG = LoggerFactory.getLogger(LogEvents.class);
    private static final String TOPIC = "log-events";
    private final KafkaTemplate<String, String> producer;

    LogEvents(KafkaTemplate<String, String> producer) {
        this.producer = producer;
    }

    @Scheduled(fixedDelayString = "PT1s")
    void send() {
        String id = UUID.randomUUID().toString();
        try {
            var metadata = producer.send(TOPIC, id, "Hello from Kubernetes").get().getRecordMetadata();
            LOG.info("EVENT_SENT id={} partition={} offset={}", id, metadata.partition(), metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            LOG.warn("Kafka send failed category={}", exception.getCause().getClass().getSimpleName());
        }
    }

    @KafkaListener(topics = TOPIC)
    void receive(ConsumerRecord<String, String> record) {
        UUID.fromString(record.key());
        LOG.info("EVENT_RECEIVED id={} partition={} offset={}", record.key(), record.partition(), record.offset());
    }
}
