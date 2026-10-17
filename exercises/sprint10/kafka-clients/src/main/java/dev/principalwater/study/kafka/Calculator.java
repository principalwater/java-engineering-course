package dev.principalwater.study.kafka;

import java.util.Scanner;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;

final class Calculator {
    private Calculator() { }

    private static void begin(KafkaProducer<String, String> producer, int number) throws Exception {
        String key = Integer.toString(Math.floorMod(number, 10));
        producer.beginTransaction();
        // Подтверждённый append позволяет увидеть отменённый ввод через read_uncommitted.
        producer.send(new ProducerRecord<>(KafkaProperties.INPUT, key, Integer.toString(number))).get();
    }

    private static void complete(KafkaProducer<String, String> producer, int number, String operation) {
        String key = Integer.toString(Math.floorMod(number, 10));
        final int result;
        try {
            result = switch (operation) {
                case "sq" -> Math.multiplyExact(number, number);
                case "abs" -> Math.absExact(number);
                default -> throw new IllegalArgumentException("Unsupported operation");
            };
        } catch (IllegalArgumentException | ArithmeticException exception) {
            producer.abortTransaction();
            System.out.printf("ABORT input=%d reason=%s%n", number, exception.getMessage());
            return;
        }
        producer.send(new ProducerRecord<>(KafkaProperties.RESULTS, key, number + " " + operation + " " + result));
        try {
            producer.commitTransaction();
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Commit outcome unknown; inspect transaction before retry", exception);
        }
        System.out.printf("COMMIT input=%d operation=%s result=%d key=%s%n", number, operation, result, key);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("calculator <unique-transactional-id>");
        }
        var properties = KafkaProperties.producer("calculator");
        properties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, Workshop.name(args[0]));
        try (var producer = new KafkaProducer<String, String>(properties); var scanner = new Scanner(System.in)) {
            producer.initTransactions();
            while (true) {
                System.out.println("Enter an integer, or EOF to stop:");
                if (!scanner.hasNextLine()) {
                    return;
                }
                final int number;
                try {
                    number = Integer.parseInt(scanner.nextLine());
                } catch (NumberFormatException exception) {
                    System.out.println("Invalid integer; try again");
                    continue;
                }
                begin(producer, number);
                System.out.println("Enter sq or abs:");
                if (!scanner.hasNextLine()) {
                    producer.abortTransaction();
                    return;
                }
                complete(producer, number, scanner.nextLine());
            }
        }
    }
}
