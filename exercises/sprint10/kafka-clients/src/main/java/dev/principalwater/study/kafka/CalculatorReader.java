package dev.principalwater.study.kafka;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;

final class CalculatorReader {
    private CalculatorReader() { }

    public static void main(String[] args) {
        if (args.length != 2 || !List.of("read_committed", "read_uncommitted").contains(args[0])) {
            throw new IllegalArgumentException("read <read_committed|read_uncommitted> <seconds:1..60>");
        }
        int seconds = Workshop.number(args[1], 1, 60);
        var properties = KafkaProperties.consumer("calculator-reader", "calculator-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, args[0]);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(KafkaProperties.INPUT, KafkaProperties.RESULTS));
            long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (System.nanoTime() < end) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    System.out.printf("VALUE topic=%s partition=%d offset=%d key=%s value=%s%n",
                            record.topic(), record.partition(), record.offset(), record.key(), record.value());
                }
            }
        }
    }
}
