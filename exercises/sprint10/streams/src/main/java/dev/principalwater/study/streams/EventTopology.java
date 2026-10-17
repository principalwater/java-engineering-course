package dev.principalwater.study.streams;

import dev.principalwater.study.streams.model.DetailedEvent;
import dev.principalwater.study.streams.model.EventInfo;
import dev.principalwater.study.streams.model.UserProfile;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerde;

@Configuration
public class EventTopology {
    public static final String NOTIFICATIONS = "notification-events";
    public static final String PROFILES = "profile-events";
    public static final String DETAILS = "detailed-events";
    public static final String PROFILE_STORE = "latest-profiles";

    @Bean
    KStream<UUID, DetailedEvent> enrichedEvents(StreamsBuilder builder) {
        var events = builder.stream(NOTIFICATIONS, Consumed.with(Serdes.UUID(), new JsonSerde<>(EventInfo.class).noTypeInfo()));
        KTable<UUID, UserProfile> profiles = builder.table(PROFILES,
                Consumed.with(Serdes.UUID(), new JsonSerde<>(UserProfile.class).noTypeInfo()),
                Materialized.as(PROFILE_STORE));
        var enriched = events.leftJoin(profiles, DetailedEvent::new);
        enriched.to(DETAILS, Produced.with(Serdes.UUID(), new JsonSerde<>(DetailedEvent.class).noTypeInfo()));
        return enriched;
    }
}
