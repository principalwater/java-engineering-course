package dev.principalwater.study.kafka.producer;

import dev.principalwater.study.kafka.api.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class TopicConfiguration {
    @Bean
    NewTopic demo() {
        return TopicBuilder.name(Topics.DEMO).partitions(3).replicas(2).config("min.insync.replicas", "2").build();
    }
    @Bean
    NewTopic orders() {
        return TopicBuilder.name(Topics.ORDERS).partitions(5).replicas(2).config("min.insync.replicas", "2").build();
    }
}
