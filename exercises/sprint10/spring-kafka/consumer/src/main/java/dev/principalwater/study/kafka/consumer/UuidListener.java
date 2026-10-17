package dev.principalwater.study.kafka.consumer;

import dev.principalwater.study.kafka.api.Topics;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Profile("uuid")
class UuidListener {
    private static final Logger LOG = LoggerFactory.getLogger(UuidListener.class);

    @KafkaListener(topics = Topics.DEMO)
    void receive(ConsumerRecord<String, String> record) {
        UUID id = UUID.fromString(record.value());
        LOG.info("UUID_RECEIVED id={} partition={} offset={}", id, record.partition(), record.offset());
    }
}
