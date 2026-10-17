package dev.principalwater.study.kafka.consumer.orders;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Service;

@Service
@Profile("orders")
public class DeadLetterRecoverer implements ConsumerRecordRecoverer {
    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRecoverer.class);
    private final DeadLetterRepository repository;

    public DeadLetterRecoverer(DeadLetterRepository repository) {
        this.repository = repository;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        String category = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
        String key = record.key() instanceof String value ? value : null;
        try {
            // Транзакция репозитория завершается до возврата recoverer; ошибка записи не разрешает commit offset.
            repository.saveAndFlush(new DeadLetterEntry(record.topic(), record.partition(), record.offset(), key, category));
            LOG.info("DLQ_STORED topic={} partition={} offset={} category={}", record.topic(), record.partition(), record.offset(), category);
        } catch (DataAccessException failure) {
            LOG.error("DLQ_FAILED topic={} partition={} offset={} category={}", record.topic(), record.partition(), record.offset(), failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
