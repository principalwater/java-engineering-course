package dev.principalwater.study.kafka;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.NotEnoughReplicasAfterAppendException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("kafka")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaProtocolsTest {
    private static final Duration WAIT = Duration.ofSeconds(45);
    private static final String PROBE = "ack-probe";
    private static Admin admin;

    @BeforeAll
    static void topics() throws Exception {
        admin = Admin.create(Map.of("bootstrap.servers", KafkaProperties.BOOTSTRAP,
                "default.api.timeout.ms", "10000", "request.timeout.ms", "5000"));
        assertEquals("uBlRBGxLT1O_ShF9v2yTlg", admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS),
                "Refuse to modify any cluster other than this owned fixture");
        await(() -> {
            try { return admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).size() == 2; }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        for (var topic : Map.of(KafkaProperties.MYSTERY, 5, KafkaProperties.INPUT, 10, KafkaProperties.RESULTS, 10, PROBE, 1).entrySet()) {
            try {
                admin.createTopics(List.of(new NewTopic(topic.getKey(), topic.getValue(), (short) 2)
                        .configs(Map.of("min.insync.replicas", "2")))).all().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) {
                    throw exception;
                }
            }
            await(() -> {
                try {
                    var partitions = admin.describeTopics(List.of(topic.getKey())).allTopicNames().get(10, TimeUnit.SECONDS)
                            .get(topic.getKey()).partitions();
                    return partitions.size() == topic.getValue()
                            && partitions.stream().allMatch(partition -> partition.replicas().size() == 2 && partition.isr().size() == 2);
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof UnknownTopicOrPartitionException) {
                        return false;
                    }
                    throw new IllegalStateException(exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                } catch (java.util.concurrent.TimeoutException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        await(() -> isr(PROBE) == 2);
    }

    @AfterAll
    static void closeAdmin() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
        }
    }

    @Test
    @Order(1)
    void transactionCommitsBothTopicsAndHidesAbortedInputFromCommittedReaders() throws Exception {
        try (var committed = reader("read_committed"); var uncommitted = reader("read_uncommitted")) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : List.of(KafkaProperties.INPUT, KafkaProperties.RESULTS)) {
                committed.partitionsFor(topic).forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
            }
            for (var consumer : List.of(committed, uncommitted)) {
                consumer.assign(partitions);
                consumer.seekToEnd(partitions);
                partitions.forEach(consumer::position);
            }
            Path log = Path.of("build/verification/calculator-transactions.log");
            java.nio.file.Files.createDirectories(log.getParent());
            var process = new ProcessBuilder(Path.of("build/install/kafka-clients/bin/kafka-clients").toAbsolutePath().toString(),
                    "calculator", "calculator-proof-" + UUID.randomUUID())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            var initial = new ArrayList<ConsumerRecord<String, String>>();
            try {
                try (var input = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    input.write("3\n");
                    input.flush();
                    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    while (initial.isEmpty() && System.nanoTime() < deadline) {
                        uncommitted.poll(Duration.ofMillis(200)).forEach(initial::add);
                    }
                    assertTrue(initial.stream().anyMatch(record -> record.topic().equals(KafkaProperties.INPUT)
                            && record.value().equals("3")), "CLI must append the number before waiting for the operation");
                    assertTrue(committed.poll(Duration.ofMillis(200)).isEmpty(), "An open transaction is not committed input");
                    input.write("sq\n-3\nabs\n7\nunsupported\n2147483647\nsq\n-2147483648\nabs\n");
                }
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Calculator process did not finish");
                assertEquals(0, process.exitValue());
            } finally {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                    }
                }
            }
            var ends = uncommitted.endOffsets(partitions);
            List<ConsumerRecord<String, String>> visible = drain(committed, ends);
            List<ConsumerRecord<String, String>> raw = new ArrayList<>(initial);
            raw.addAll(drain(uncommitted, ends));
            assertEquals(Set.of("input-numbers:3", "input-numbers:-3", "results:3 sq 9", "results:-3 abs 3"), values(visible));
            assertEquals(4, visible.size());
            assertEquals(Set.of("input-numbers:3", "input-numbers:-3", "results:3 sq 9", "results:-3 abs 3",
                    "input-numbers:7", "input-numbers:2147483647", "input-numbers:-2147483648"), values(raw));
            assertEquals(7, raw.size());
            assertTrue(visible.stream().filter(record -> record.value().startsWith("-3")).allMatch(record -> record.key().equals("7")));
        }
    }

    @Test
    @Order(2)
    void allAcknowledgementsRequireMinimumIsrAndRecoverAfterBrokerRestart() throws Exception {
        var properties = KafkaProperties.producer("ack-proof");
        // Для отрицательного случая нужен немедленный broker error, а не истечение общего retry budget.
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        properties.put(ProducerConfig.RETRIES_CONFIG, 0);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000);
        boolean stopped = false;
        try (var producer = new KafkaProducer<String, String>(properties)) {
            timings(producer, "two-isr");
            stopped = true;
            compose("stop", "broker2");
            await(() -> isr(PROBE) == 1);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> producer.send(new ProducerRecord<>(PROBE, "probe", "rejected-at-isr-one")).get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof NotEnoughReplicasException
                    || failure.getCause() instanceof NotEnoughReplicasAfterAppendException, failure.toString());
            minimumIsr("1");
            timings(producer, "one-isr");
        } finally {
            if (stopped) {
                compose("up", "-d", "--wait", "--wait-timeout", "120", "broker2");
            }
            minimumIsr("2");
            await(() -> isr(PROBE) == 2);
        }
    }

    private static void timings(KafkaProducer<String, String> producer, String label) throws Exception {
        final int warmups = 5;
        final int samples = 20;
        var acknowledgements = new ArrayList<Double>();
        var deliveries = new ArrayList<Long>();
        try (var consumer = reader("read_committed")) {
            var partition = new TopicPartition(PROBE, 0);
            consumer.assign(List.of(partition));
            consumer.seekToEnd(List.of(partition));
            consumer.position(partition);
            for (int sample = 0; sample < warmups + samples; sample++) {
                long start = System.nanoTime();
                long sentAt = System.currentTimeMillis();
                var metadata = producer.send(new ProducerRecord<>(PROBE, null, sentAt, "sample", label))
                        .get(10, TimeUnit.SECONDS);
                double ackMillis = (System.nanoTime() - start) / 1_000_000.0;
                ConsumerRecord<String, String> received = null;
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (received == null) {
                    for (var record : consumer.poll(Duration.ofMillis(200))) {
                        if (record.offset() == metadata.offset()) {
                            received = record;
                        }
                    }
                    assertTrue(System.nanoTime() < deadline, "Acknowledged record was not delivered");
                }
                assertEquals(sentAt, received.timestamp());
                assertEquals(label, received.value());
                long deliveryMillis = System.currentTimeMillis() - received.timestamp();
                assertTrue(deliveryMillis >= 0, "Wall clock moved backwards during the measurement");
                if (sample >= warmups) {
                    acknowledgements.add(ackMillis);
                    deliveries.add(deliveryMillis);
                }
            }
        }
        Collections.sort(acknowledgements);
        Collections.sort(deliveries);
        String report = String.format(Locale.ROOT,
                "{\"mode\":\"%s\",\"samples\":%d,\"warmups\":%d,\"ack_p50_ms\":%.3f,\"ack_p95_ms\":%.3f,"
                        + "\"delivery_p50_ms\":%d,\"delivery_p95_ms\":%d}\n",
                label, samples, warmups, acknowledgements.get(9), acknowledgements.get(18), deliveries.get(9), deliveries.get(18));
        Path path = Path.of("build/verification/" + label + ".json");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, report);
        System.out.print(report);
    }

    private static KafkaConsumer<String, String> reader(String isolation) {
        var properties = KafkaProperties.consumer("proof-" + isolation, "proof-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(properties);
    }

    private static List<ConsumerRecord<String, String>> drain(KafkaConsumer<String, String> consumer,
                                                             Map<TopicPartition, Long> ends) {
        var records = new ArrayList<ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            if (ends.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue())) {
                return records;
            }
            assertTrue(System.nanoTime() < deadline, "Reader did not pass the completed transaction boundaries");
        }
    }

    private static Set<String> values(List<ConsumerRecord<String, String>> records) {
        var result = new HashSet<String>();
        records.forEach(record -> result.add(record.topic() + ":" + record.value()));
        return result;
    }

    private static int isr(String topic) {
        try {
            return admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS)
                    .get(topic).partitions().getFirst().isr().size();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void minimumIsr(String value) throws Exception {
        var resource = new ConfigResource(ConfigResource.Type.TOPIC, PROBE);
        admin.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(
                new ConfigEntry("min.insync.replicas", value), AlterConfigOp.OpType.SET)))).all().get(10, TimeUnit.SECONDS);
    }

    private static void compose(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "-p", "java-sprint10-kafka", "-f",
                Path.of("../broker/compose.yml").toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).inheritIO().start();
        try {
            assertTrue(process.waitFor(150, TimeUnit.SECONDS), "Owned broker command timed out");
            assertEquals(0, process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for ISR/cluster readiness");
            Thread.sleep(200);
        }
    }
}
