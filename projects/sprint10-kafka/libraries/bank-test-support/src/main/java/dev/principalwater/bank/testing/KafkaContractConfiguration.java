package dev.principalwater.bank.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.contract.verifier.converter.YamlContract;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierReceiver;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessage;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessaging;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

// SCC использует настоящий Kafka transport без добавления Spring Cloud Stream в приложение.
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContractConfiguration {
    public static final String KEY_HEADER = "kafka_messageKey";

    @Bean
    ContractVerifierObjectMapper contractVerifierObjectMapper(ObjectMapper mapper) {
        return new ContractVerifierObjectMapper(mapper);
    }

    @Bean
    Transport kafkaContractTransport(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        return new Transport(kafka, mapper);
    }

    @Bean
    ContractVerifierMessaging<ContractVerifierMessage> contractVerifierMessaging(
            Transport transport) {
        return new ContractVerifierMessaging<>(transport, transport) {
            @Override
            protected ContractVerifierMessage convert(ContractVerifierMessage message) {
                return message;
            }
        };
    }

    public static final class Transport
            implements MessageVerifierSender<ContractVerifierMessage>,
                    MessageVerifierReceiver<ContractVerifierMessage> {
        private final KafkaTemplate<String, String> kafka;
        private final ObjectMapper mapper;

        public Transport(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
            this.kafka = kafka;
            this.mapper = mapper;
        }

        @Override
        public void send(
                ContractVerifierMessage message, String destination, YamlContract contract) {
            send(message.getPayload(), message.getHeaders(), destination, contract);
        }

        @Override
        public <T> void send(
                T payload, Map<String, Object> headers, String destination, YamlContract contract) {
            Object key = headers.get(KEY_HEADER);
            if (key == null) throw new IllegalArgumentException("Contract is missing Kafka key");
            String json;
            try {
                json =
                        payload instanceof String text
                                ? text
                                : payload instanceof byte[] bytes
                                        ? new String(bytes, StandardCharsets.UTF_8)
                                        : mapper.writeValueAsString(payload);
            } catch (JsonProcessingException failure) {
                throw new IllegalArgumentException("Invalid contract payload", failure);
            }
            try {
                kafka.send(destination, key.toString(), json).get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot publish contract message", failure);
            }
        }

        @Override
        public ContractVerifierMessage receive(
                String destination, long timeout, TimeUnit unit, YamlContract contract) {
            var records = KafkaFixture.records(destination, 1);
            if (records.isEmpty()) return null;
            var record = records.getFirst();
            return new ContractVerifierMessage(record.value(), Map.of(KEY_HEADER, record.key()));
        }

        @Override
        public ContractVerifierMessage receive(String destination, YamlContract contract) {
            return receive(destination, 30, TimeUnit.SECONDS, contract);
        }
    }
}
