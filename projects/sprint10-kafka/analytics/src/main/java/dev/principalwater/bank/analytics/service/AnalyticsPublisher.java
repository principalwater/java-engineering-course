package dev.principalwater.bank.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.contract.BankEvent;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsPublisher {
    // delivery.timeout 4s плюс metadata max.block 1s; ожидание не удерживает lease бесконечно.
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(5);
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public AnalyticsPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void publish(BankEvent event) {
        try {
            // owner key выбирает общий partition, но не порядок ledger; replay дедуплицирует inbox.
            kafka.send(
                            AnalyticsProjection.TOPIC,
                            event.ownerId().toString(),
                            mapper.writeValueAsString(event))
                    .get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.BROKER, failure);
        } catch (JsonProcessingException | ExecutionException | TimeoutException failure) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.BROKER, failure);
        } catch (RuntimeException failure) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.BROKER, failure);
        }
    }
}
