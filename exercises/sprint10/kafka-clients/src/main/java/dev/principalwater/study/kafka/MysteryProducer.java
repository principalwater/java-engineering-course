package dev.principalwater.study.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

final class MysteryProducer {
    private MysteryProducer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !(args[4].equals("normal") || args[4].equals("batch"))) {
            throw new IllegalArgumentException("producer <name> <workers:1..10> <records:1..1000> <interval-ms:1..2000> <normal|batch>");
        }
        String base = Workshop.name(args[0]);
        int workers = Workshop.number(args[1], 1, 10);
        int records = Workshop.number(args[2], 1, 1000);
        int interval = Workshop.number(args[3], 1, 2000);
        var properties = KafkaProperties.producer(base);
        if (args[4].equals("batch")) {
            properties.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072);
            properties.put(ProducerConfig.LINGER_MS_CONFIG, 2000);
        }
        var failure = new AtomicReference<Exception>();
        var producer = new KafkaProducer<String, String>(properties);
        Thread hook = new Thread(() -> producer.close(Duration.ofSeconds(10)), "producer-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try (producer; var executor = Executors.newFixedThreadPool(workers)) {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 1; worker <= workers; worker++) {
                String name = base + "-" + worker;
                tasks.add(executor.submit(() -> {
                    for (int record = 0; record < records && failure.get() == null; record++) {
                        String key = "key" + ThreadLocalRandom.current().nextInt(1, 11);
                        String value = name + " " + ThreadLocalRandom.current().nextInt(100);
                        producer.send(new ProducerRecord<>(KafkaProperties.MYSTERY, null, System.currentTimeMillis(), key, value),
                                (metadata, error) -> {
                                    if (error != null) {
                                        failure.compareAndSet(null, error);
                                    } else {
                                        System.out.printf("SENT client=%s key=%s partition=%d offset=%d%n",
                                                name, key, metadata.partition(), metadata.offset());
                                    }
                                });
                        try {
                            Thread.sleep(interval);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
            producer.flush();
            if (failure.get() != null) {
                throw new IllegalStateException("Kafka send failed", failure.get());
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // При внешнем SIGTERM клиент закрывает shutdown hook.
            }
        }
    }
}
