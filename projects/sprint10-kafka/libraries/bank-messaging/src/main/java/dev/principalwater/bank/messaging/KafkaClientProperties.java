package dev.principalwater.bank.messaging;

import java.util.HashMap;
import java.util.Map;

public final class KafkaClientProperties {
    private KafkaClientProperties() {}

    public static Map<String, Object> secured(
            Map<String, Object> properties, String user, String password) {
        if (user == null
                || user.isBlank()
                || password == null
                || password.isBlank()
                || user.codePoints().anyMatch(Character::isISOControl)
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Kafka credentials");
        }
        Map<String, Object> result = new HashMap<>(properties);
        String mechanism = result.getOrDefault("sasl.mechanism", "PLAIN").toString();
        String module =
                switch (mechanism) {
                    case "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule";
                    case "SCRAM-SHA-256", "SCRAM-SHA-512" ->
                            "org.apache.kafka.common.security.scram.ScramLoginModule";
                    default ->
                            throw new IllegalArgumentException("Unsupported Kafka SASL mechanism");
                };
        result.put(
                "sasl.jaas.config",
                module
                        + " required username=\""
                        + escape(user)
                        + "\" password=\""
                        + escape(password)
                        + "\";");
        return result;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
