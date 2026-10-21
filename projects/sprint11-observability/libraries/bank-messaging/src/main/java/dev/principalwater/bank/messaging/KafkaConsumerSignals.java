package dev.principalwater.bank.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.kafka.event.ConsumerRetryAuthEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.KafkaEvent;

// Штатные Kafka-логи могут включать record/cause; общий сигнал публикует только закрытую категорию.
public class KafkaConsumerSignals implements ApplicationListener<KafkaEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerSignals.class);

    @Override
    public void onApplicationEvent(KafkaEvent event) {
        if (event instanceof ConsumerStoppedEvent stopped
                && stopped.getReason() != ConsumerStoppedEvent.Reason.NORMAL) {
            LOG.warn("Kafka consumer stopped: {}", stopped.getReason());
        } else if (event instanceof ConsumerRetryAuthEvent retry) {
            LOG.warn("Kafka consumer authorization retry: {}", retry.getReason());
        }
    }
}
