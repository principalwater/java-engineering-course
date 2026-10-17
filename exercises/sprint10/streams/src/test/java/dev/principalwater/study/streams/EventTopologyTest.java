package dev.principalwater.study.streams;

import dev.principalwater.study.streams.model.DetailedEvent;
import dev.principalwater.study.streams.model.EventInfo;
import dev.principalwater.study.streams.model.UserProfile;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static dev.principalwater.study.streams.EventTopology.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.kafka.test.hamcrest.KafkaMatchers.hasKey;
import static org.springframework.kafka.test.hamcrest.KafkaMatchers.hasValue;

@SpringBootTest(properties = {
        "spring.kafka.streams.application-id=enrichment-${random.uuid}",
        "spring.kafka.streams.properties.commit.interval.ms=100"
})
@EmbeddedKafka(partitions = 1, topics = {PROFILES, NOTIFICATIONS, DETAILS},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class EventTopologyTest {
    @Autowired KafkaTemplate<UUID, Object> producer;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired StreamsBuilderFactoryBean streams;

    @ParameterizedTest(name = "profile revisions={0}")
    @ValueSource(ints = {0, 1, 2})
    void enrichesWithLatestProfileForTheSameKey(int revisions) throws Exception {
        UUID key = UUID.randomUUID();
        var event = new EventInfo("Logged in", "New device");
        UserProfile expected = null;
        try (var consumer = new DefaultKafkaConsumerFactory<>(KafkaTestUtils.consumerProps(
                "result-" + key, "false", broker), new UUIDDeserializer(),
                new JsonDeserializer<>(DetailedEvent.class, false)).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, true, DETAILS);
            // seekToEnd ленивый: фиксируем позицию до отправки fixtures, иначе новый результат можно пропустить.
            consumer.assignment().forEach(consumer::position);
            UUID otherKey = UUID.randomUUID();
            var otherProfile = new UserProfile("Other", "Moscow");
            producer.send(PROFILES, otherKey, otherProfile).get();
            awaitProfile(otherKey, otherProfile);
            if (revisions > 0) {
                expected = new UserProfile("Vasily", "Kaliningrad");
                producer.send(PROFILES, key, expected).get();
                awaitProfile(key, expected);
            }
            if (revisions > 1) {
                expected = new UserProfile("Vasily", "Krasnodar");
                producer.send(PROFILES, key, expected).get();
                awaitProfile(key, expected);
            }
            // Подтверждение producer не означает обновление KTable: сначала ждём фактическое состояние store.
            producer.send(NOTIFICATIONS, key, event).get();
            var result = KafkaTestUtils.getSingleRecord(consumer, DETAILS, Duration.ofSeconds(15));
            org.hamcrest.MatcherAssert.assertThat(result, hasKey(key));
            org.hamcrest.MatcherAssert.assertThat(result, hasValue(new DetailedEvent(event, expected)));
        }
    }

    private void awaitProfile(UUID key, UserProfile expected) {
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(() -> {
            ReadOnlyKeyValueStore<UUID, UserProfile> store = streams.getKafkaStreams().store(
                    StoreQueryParameters.fromNameAndType(PROFILE_STORE, QueryableStoreTypes.keyValueStore()));
            assertThat(store.get(key)).isEqualTo(expected);
        });
    }
}
