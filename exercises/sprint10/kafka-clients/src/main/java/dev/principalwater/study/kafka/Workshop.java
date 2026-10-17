package dev.principalwater.study.kafka;

import java.util.Arrays;

public final class Workshop {
    private Workshop() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected producer, consumer, calculator or read");
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "producer" -> MysteryProducer.main(rest);
            case "consumer" -> MysteryConsumer.main(rest);
            case "calculator" -> Calculator.main(rest);
            case "read" -> CalculatorReader.main(rest);
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }

    static int number(String value, int minimum, int maximum) {
        int result = Integer.parseInt(value);
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("Argument outside allowed range");
        }
        return result;
    }

    static String name(String value) {
        if (!value.matches("[a-zA-Z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("Invalid client or group name");
        }
        return value;
    }
}
