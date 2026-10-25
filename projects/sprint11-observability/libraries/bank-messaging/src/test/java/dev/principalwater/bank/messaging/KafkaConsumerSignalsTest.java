package dev.principalwater.bank.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.event.ConsumerRetryAuthEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;

@ExtendWith(OutputCaptureExtension.class)
class KafkaConsumerSignalsTest {
    @Test
    void consumerFailuresPublishOnlyTypedCategoriesAndNormalShutdownStaysSilent(
            CapturedOutput output) {
        String privateSource = "Bearer private-consumer-token";
        try (var context = new AnnotationConfigApplicationContext(KafkaConsumerSignals.class)) {
            String before = output.getAll();
            context.publishEvent(
                    new ConsumerStoppedEvent(
                            privateSource, privateSource, ConsumerStoppedEvent.Reason.NORMAL));
            assertThat(output.getAll()).isEqualTo(before);
            context.publishEvent(
                    new ConsumerStoppedEvent(
                            privateSource, privateSource, ConsumerStoppedEvent.Reason.AUTH));
            context.publishEvent(
                    new ConsumerRetryAuthEvent(
                            privateSource,
                            privateSource,
                            ConsumerRetryAuthEvent.Reason.AUTHORIZATION));
            assertThat(output.getAll())
                    .contains(
                            "Kafka consumer stopped: AUTH",
                            "Kafka consumer authorization retry: AUTHORIZATION")
                    .doesNotContain(privateSource);
        }
    }
}
