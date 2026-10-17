package dev.principalwater.study.kafka.producer;

import dev.principalwater.study.kafka.api.Order;
import java.time.Instant;
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
@Profile("orders")
class OrderPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OrderPublisher.class);
    private final KafkaTemplate<String, Order> template;

    OrderPublisher(KafkaTemplate<String, Order> template) {
        this.template = template;
    }

    @Scheduled(fixedDelayString = "PT0.5s")
    void publish() {
        var data = new Order(UUID.randomUUID(), "anonymous", Instant.now());
        try {
            // Ожидание подтверждения ограничивает число незавершённых отправок одним сообщением.
            var metadata = template.send(Topics.ORDERS, data.id().toString(), data).get().getRecordMetadata();
            LOG.info("ORDER_SENT id={} partition={} offset={}", data.id(), metadata.partition(), metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            LOG.error("Kafka send failed category={}", exception.getCause().getClass().getSimpleName());
        }
    }
}
