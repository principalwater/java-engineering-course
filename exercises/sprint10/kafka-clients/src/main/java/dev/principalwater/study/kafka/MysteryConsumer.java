package dev.principalwater.study.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

final class MysteryConsumer {
    private MysteryConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("consumer <name> <workers:1..10> <seconds:1..300> <group>");
        }
        String base = Workshop.name(args[0]);
        int workers = Workshop.number(args[1], 1, 10);
        int seconds = Workshop.number(args[2], 1, 300);
        String group = Workshop.name(args[3]);
        var finished = new CountDownLatch(workers);
        var stopping = new AtomicBoolean();
        var consumers = new CopyOnWriteArrayList<KafkaConsumer<String, String>>();
        Runnable wakeup = () -> {
            stopping.set(true);
            consumers.forEach(consumer -> {
                try {
                    consumer.wakeup();
                } catch (IllegalStateException ignored) {
                    // Snapshot списка мог сохранить уже закрытый экземпляр.
                }
            });
        };
        Thread hook = new Thread(() -> {
            wakeup.run();
            try {
                // JVM завершает обычные потоки после hooks: одного wakeup недостаточно для close/commit.
                if (!finished.await(12, TimeUnit.SECONDS)) {
                    System.err.println("Consumer shutdown deadline exceeded");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "consumer-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        var executor = Executors.newFixedThreadPool(workers);
        try {
            var completed = new ExecutorCompletionService<Void>(executor);
            for (int worker = 1; worker <= workers; worker++) {
                String name = base + "-" + worker;
                completed.submit(() -> {
                    var properties = KafkaProperties.consumer(name, group);
                    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
                    properties.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 20000);
                    properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000);
                    properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2000);
                    properties.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                            "org.apache.kafka.clients.consumer.RangeAssignor");
                    var consumer = new KafkaConsumer<String, String>(properties);
                    consumers.add(consumer);
                    try {
                        consumer.subscribe(List.of(KafkaProperties.MYSTERY), new ConsumerRebalanceListener() {
                            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                                System.out.printf("REVOKE client=%s partitions=%s%n", name, partitions);
                            }
                            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                                System.out.printf("ASSIGN client=%s partitions=%s%n", name,
                                        partitions.stream().map(TopicPartition::partition).sorted().toList());
                            }
                        });
                        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
                        try {
                            while (!stopping.get() && System.nanoTime() < deadline) {
                                System.out.printf("POLL client=%s%n", name);
                                for (var record : consumer.poll(Duration.ofSeconds(5))) {
                                    System.out.printf("RECORD client=%s key=%s partition=%d offset=%d delay_ms=%d value=%s%n",
                                            name, record.key(), record.partition(), record.offset(),
                                            System.currentTimeMillis() - record.timestamp(), record.value());
                                }
                            }
                        } catch (WakeupException exception) {
                            if (!stopping.get()) {
                                throw exception;
                            }
                        }
                    } finally {
                        consumers.remove(consumer);
                        try {
                            consumer.close(Duration.ofSeconds(5));
                        } finally {
                            finished.countDown();
                        }
                    }
                    return null;
                });
            }
            for (int worker = 0; worker < workers; worker++) {
                completed.take().get();
            }
        } finally {
            wakeup.run();
            executor.shutdown();
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // Shutdown hook уже выполняется при внешнем SIGTERM.
            }
        }
    }
}
