package dev.principalwater.study.kafka.consumer;

import dev.principalwater.study.kafka.consumer.orders.DeadLetterRecoverer;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("orders")
class KafkaErrorConfiguration {
    @Bean
    CommonErrorHandler errors(DeadLetterRecoverer recoverer) {
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(200, 2));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        // Собственные сообщения сохраняют metadata/category, без payload из framework stack trace.
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory, CommonErrorHandler errors) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        return factory;
    }
}
