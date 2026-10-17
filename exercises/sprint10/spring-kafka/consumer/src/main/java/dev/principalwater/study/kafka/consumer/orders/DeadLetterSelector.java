package dev.principalwater.study.kafka.consumer.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("orders")
class DeadLetterSelector {
    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterSelector.class);
    private final DeadLetterRepository repository;

    DeadLetterSelector(DeadLetterRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "PT5s")
    void showLatest() {
        repository.findTop20ByOrderByFailedAtDescIdAsc().forEach(entry -> LOG.info("DLQ_TAIL {}", entry));
    }
}
