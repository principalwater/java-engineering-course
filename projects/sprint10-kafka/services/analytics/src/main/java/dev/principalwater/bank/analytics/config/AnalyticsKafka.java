package dev.principalwater.bank.analytics.config;

import dev.principalwater.bank.messaging.KafkaClientProperties;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.event.ConsumerRetryAuthEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.KafkaEvent;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration(proxyBeanMethods = false)
public class AnalyticsKafka {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsKafka.class);
    // Один partition читается одним потоком каждой группы; между конечными попытками даём
    // зависимости восстановиться.
    private static final int CONSUMERS_PER_GROUP = 1;
    private static final long POLL_TIMEOUT_MS = 1000;
    private static final long RETRY_BACKOFF_MS = 5000;
    // 4s delivery deadline должен завершиться внутри 5s close, не съедая стандартные 30s graceful
    // shutdown.
    private static final int PRODUCER_CLOSE_SECONDS = 5;
    private static final Duration AUTHENTICATION_RETRY = Duration.ofSeconds(10);
    private final KafkaProperties properties;
    private final String user;
    private final String password;

    public AnalyticsKafka(
            KafkaProperties properties,
            @Value("${bank.kafka.user}") String user,
            @Value("${bank.kafka.password}") String password) {
        this.properties = properties;
        this.user = user;
        this.password = password;
    }

    @Bean
    ProducerFactory<String, String> analyticsProducerFactory() {
        DefaultKafkaProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(secured(properties.buildProducerProperties()));
        factory.setPhysicalCloseTimeout(PRODUCER_CLOSE_SECONDS);
        return factory;
    }

    @Bean
    KafkaTemplate<String, String> analyticsKafkaTemplate(ProducerFactory<String, String> factory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        // Штатный LoggingProducerListener включает содержимое записи; здесь достаточно безопасного
        // класса ошибки.
        template.setProducerListener(
                new ProducerListener<>() {
                    @Override
                    public void onError(
                            ProducerRecord<String, String> record,
                            RecordMetadata metadata,
                            Exception failure) {
                        LOG.warn(
                                "Analytics Kafka send unavailable: {}",
                                failure.getClass().getSimpleName());
                    }
                });
        return template;
    }

    @Bean
    ConsumerFactory<String, String> analyticsConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(secured(properties.buildConsumerProperties()));
    }

    @Bean
    ApplicationListener<KafkaEvent> analyticsConsumerFailureLogger() {
        return event -> {
            if (event instanceof ConsumerStoppedEvent stopped
                    && stopped.getReason() != ConsumerStoppedEvent.Reason.NORMAL) {
                LOG.warn("Analytics consumer stopped: {}", stopped.getReason());
            } else if (event instanceof ConsumerRetryAuthEvent retry) {
                LOG.warn("Analytics consumer authorization retry: {}", retry.getReason());
            }
        };
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> factory) {
        ConcurrentKafkaListenerContainerFactory<String, String> listeners =
                new ConcurrentKafkaListenerContainerFactory<>();
        listeners.setConsumerFactory(factory);
        listeners.setConcurrency(CONSUMERS_PER_GROUP);
        listeners.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        listeners.getContainerProperties().setPollTimeout(POLL_TIMEOUT_MS);
        // При восстановлении ACL/брокера consumer продолжает работу, а не останавливается навсегда.
        listeners.getContainerProperties().setAuthExceptionRetryInterval(AUTHENTICATION_RETRY);
        // Штатная пауза потока 5s меньше max.poll.interval 60s; seekAfterError=false удерживает
        // только одну запись.
        DefaultErrorHandler errors =
                new DefaultErrorHandler(
                        new FixedBackOff(RETRY_BACKOFF_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setSeekAfterError(false);
        errors.setClassifications(Map.of(), true);
        // Штатный logger контейнера отключён в properties: даже DEBUG не должен раскрывать
        // record/cause.
        errors.setLogLevel(KafkaException.Level.DEBUG);
        errors.setRetryListeners(
                (RetryListener)
                        (record, failure, attempt) ->
                                LOG.warn(
                                        "Analytics consumer dependency unavailable: {}",
                                        failure.getCause() == null
                                                ? failure.getClass().getSimpleName()
                                                : failure.getCause().getClass().getSimpleName()));
        listeners.setCommonErrorHandler(errors);
        return listeners;
    }

    private Map<String, Object> secured(Map<String, Object> configuration) {
        return KafkaClientProperties.secured(configuration, user, password);
    }
}
