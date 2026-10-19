package dev.principalwater.bank.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

// Один брокер на JVM: тесты перезапускают консьюмеры и очищают только свои топики.
public final class KafkaFixture {
    public static final Duration WAIT = Duration.ofSeconds(30);
    private static final int PARTITIONS = 2;
    private static final KafkaContainer BROKER =
            new KafkaContainer("apache/kafka:4.2.2")
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withCreateContainerCmdModifier(
                            command ->
                                    command.getHostConfig()
                                            .withMemory(768L * 1024 * 1024)
                                            .withNanoCPUs(1_000_000_000L));

    static {
        BROKER.start();
    }

    private KafkaFixture() {}

    public static String bootstrapServers() {
        return BROKER.getBootstrapServers();
    }

    public static Admin admin() {
        return Admin.create(
                Map.of(
                        "bootstrap.servers",
                        bootstrapServers(),
                        "request.timeout.ms",
                        5000,
                        "default.api.timeout.ms",
                        10000));
    }

    public static void topics(String... topics) {
        try (Admin admin = admin()) {
            var existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            List<NewTopic> missing = new ArrayList<>();
            for (String topic : topics)
                if (!existing.contains(topic))
                    missing.add(new NewTopic(topic, PARTITIONS, (short) 1));
            if (!missing.isEmpty()) admin.createTopics(missing).all().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize Kafka fixture", failure);
        }
    }

    public static long endOffset(String topic) {
        try (Admin admin = admin()) {
            Map<TopicPartition, OffsetSpec> offsets = new java.util.HashMap<>();
            for (TopicPartition partition : partitions(topic))
                offsets.put(partition, OffsetSpec.latest());
            return admin.listOffsets(offsets).all().get(10, TimeUnit.SECONDS).values().stream()
                    .mapToLong(value -> value.offset())
                    .sum();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Kafka fixture offset", failure);
        }
    }

    public static long committed(String group, String topic) {
        try (Admin admin = admin()) {
            var offsets =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);
            return partitions(topic).stream()
                    .map(offsets::get)
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(OffsetAndMetadata::offset)
                    .sum();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Kafka fixture group", failure);
        }
    }

    public static void clear(String... topics) {
        try (Admin admin = admin()) {
            Map<TopicPartition, OffsetSpec> latest = new java.util.HashMap<>();
            for (String topic : topics)
                for (TopicPartition partition : partitions(topic))
                    latest.put(partition, OffsetSpec.latest());
            Map<TopicPartition, RecordsToDelete> deletes = new java.util.HashMap<>();
            admin.listOffsets(latest)
                    .all()
                    .get(10, TimeUnit.SECONDS)
                    .forEach(
                            (partition, offset) ->
                                    deletes.put(
                                            partition,
                                            RecordsToDelete.beforeOffset(offset.offset())));
            admin.deleteRecords(deletes).all().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot clear Kafka fixture", failure);
        }
    }

    private static List<TopicPartition> partitions(String topic) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (int index = 0; index < PARTITIONS; index++)
            partitions.add(new TopicPartition(topic, index));
        return partitions;
    }

    public static List<ConsumerRecord<String, String>> records(String topic, int count) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = partitions(topic);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + WAIT.toNanos();
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (records.size() < count && System.nanoTime() < deadline)
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            return records;
        }
    }

    public static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "fixture-" + UUID.randomUUID(),
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                        10000,
                        ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        5000));
    }
}
