package dev.principalwater.study.kafka.consumer.orders;

import dev.principalwater.study.kafka.api.Order;
import dev.principalwater.study.kafka.api.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
@Profile("orders")
class OrderListener {
    private static final Logger LOG = LoggerFactory.getLogger(OrderListener.class);

    @KafkaListener(topics = Topics.ORDERS)
    void receive(ConsumerRecord<String, Order> record, @Header(KafkaHeaders.DELIVERY_ATTEMPT) int attempt) {
        Order order = record.value();
        if (order == null) {
            throw new IllegalArgumentException("Order tombstone is not supported");
        }
        LOG.info("ORDER_SEEN id={} attempt={} partition={} offset={}", order.id(), attempt, record.partition(), record.offset());
        // Случайный UUID задаёт стабильный сценарий: повтор не меняет категорию ошибки.
        int scenario = (int) Math.floorMod(order.id().getLeastSignificantBits(), 10);
        if (scenario == 9) {
            throw new IllegalArgumentException("Simulated invalid order structure");
        }
        if (scenario >= 6 && (scenario == 8 || attempt <= 2)) {
            LOG.warn("ORDER_TRANSIENT id={} attempt={}", order.id(), attempt);
            throw new IllegalStateException("Simulated dependency failure");
        }
        LOG.info("ORDER_OK id={} attempt={} partition={} offset={}", order.id(), attempt, record.partition(), record.offset());
    }
}
