package dev.principalwater.study.coffee;

import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CoffeeManagement {
    private static final Logger LOG = LogManager.getLogger(CoffeeManagement.class);
    private static final Set<String> VOLUMES = Set.of("0,2", "0,3", "0,4");
    private static final String[] SORTS = {"Бразилия", "Эфиопия"};
    private static final String[] TYPES = {"Эспрессо", "Американо", "Капучино"};

    private CoffeeManagement() { }

    public static void main(String[] args) {
        try (var input = new Scanner(System.in)) {
            while (input.hasNextLine()) {
                String username = input.nextLine();
                if (!input.hasNextLine()) {
                    throw new IllegalArgumentException("Expected volume after username");
                }
                String volume = input.nextLine();
                // Имена ограничены, чтобы пользовательский ввод не создавал новые строки лога.
                if (!username.matches("[a-zA-Z0-9_.-]{1,64}")) {
                    throw new IllegalArgumentException("Invalid username");
                }
                var random = ThreadLocalRandom.current();
                String sort = SORTS[random.nextInt(SORTS.length)];
                String type = TYPES[random.nextInt(TYPES.length)];
                if (VOLUMES.contains(volume)) {
                    LOG.info("{} {}, {}: {}", username, sort, type, volume);
                } else {
                    // Ошибочный ввод не копируется в журнал: он может содержать произвольный текст.
                    LOG.error("{} {}, {}: invalid", username, sort, type);
                }
            }
        }
    }
}
