package dev.principalwater.bank.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.contract.BankTopics;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaMessagingConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaMessagingConfiguration.class);

    @Bean
    ProducerFactory<String, String> bankProducerFactory(
            KafkaProperties properties,
            @Value("${bank.kafka.user}") String user,
            @Value("${bank.kafka.password}") String password) {
        DefaultKafkaProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(
                        KafkaClientProperties.secured(
                                properties.buildProducerProperties(), user, password));
        factory.setPhysicalCloseTimeout(5);
        return factory;
    }

    @Bean
    KafkaTemplate<String, String> bankKafkaTemplate(ProducerFactory<String, String> factory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        template.setProducerListener(
                new ProducerListener<>() {
                    @Override
                    public void onError(
                            ProducerRecord<String, String> record,
                            RecordMetadata metadata,
                            Exception failure) {
                        LOG.warn("Kafka send unavailable: {}", failure.getClass().getSimpleName());
                    }
                });
        return template;
    }

    @Bean
    ConsumerFactory<String, String> bankConsumerFactory(
            KafkaProperties properties,
            @Value("${bank.kafka.user}") String user,
            @Value("${bank.kafka.password}") String password) {
        return new DefaultKafkaConsumerFactory<>(
                KafkaClientProperties.secured(
                        properties.buildConsumerProperties(), user, password));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> factory,
            KafkaTemplate<String, String> kafka,
            KafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> listeners =
                new ConcurrentKafkaListenerContainerFactory<>();
        listeners.setConsumerFactory(factory);
        listeners.setConcurrency(1);
        listeners.setAutoStartup(properties.getListener().isAutoStartup());
        listeners.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        listeners.getContainerProperties().setPollTimeout(1000);
        listeners.getContainerProperties().setAuthExceptionRetryInterval(Duration.ofSeconds(10));
        DeadLetterPublishingRecoverer dlt =
                new DeadLetterPublishingRecoverer(
                        kafka,
                        (record, failure) ->
                                new TopicPartition(
                                        record.topic() + BankTopics.DLT_SUFFIX,
                                        record.partition()));
        dlt.setPartitionInfoTimeout(Duration.ofSeconds(1));
        dlt.setFailIfSendResultIsError(true);
        dlt.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        dlt.setLogRecoveryRecord(false);
        // Текст JSON/parser exception и credentials не должны стать диагностическими headers.
        dlt.setExceptionHeadersCreator((headers, exception, isKey, headerNames) -> {});
        DefaultErrorHandler errors =
                new DefaultErrorHandler(
                        dlt, new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.addNotRetryableExceptions(MessageRejected.class);
        errors.setSeekAfterError(false);
        errors.setRetryListeners(
                (record, failure, attempt) ->
                        LOG.warn(
                                "Kafka consumer unavailable: {}",
                                failure.getClass().getSimpleName()));
        listeners.setCommonErrorHandler(errors);
        return listeners;
    }

    @Bean
    MessageCodec messageCodec(ObjectMapper mapper, Validator validator) {
        return new MessageCodec(mapper, validator);
    }

    @Bean
    KafkaOutbox kafkaOutbox(
            JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String, String> kafka) {
        return new KafkaOutbox(jdbc, mapper, kafka);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "bank.delivery.enabled",
            havingValue = "true",
            matchIfMissing = true)
    DeliveryWorkers deliveryWorkers(List<RecoverableDelivery> deliveries) {
        return new DeliveryWorkers(deliveries);
    }
}
