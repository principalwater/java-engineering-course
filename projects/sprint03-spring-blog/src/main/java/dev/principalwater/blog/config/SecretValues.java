package dev.principalwater.blog.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

public final class SecretValues {
    private static final String FILE_SUFFIX = "_FILE";

    private SecretValues() {
    }

    public static String read(Environment environment, String key, String defaultValue) {
        String value = environment.getProperty(key);
        String file = environment.getProperty(key + FILE_SUFFIX);
        if (value != null && file != null) {
            throw new IllegalArgumentException("Задайте только " + key + " или " + key + FILE_SUFFIX);
        }
        if (file != null) {
            // Локальный Compose монтирует Docker Secrets; для рабочего окружения предпочтителен внешний Vault/secret manager.
            try {
                value = Files.readString(Path.of(file), StandardCharsets.UTF_8);
                // Терминальный перевод строки файла не является частью пароля; пробелы сохраняются.
                if (value.endsWith("\n")) {
                    value = value.substring(0, value.length() - 1);
                    if (value.endsWith("\r")) {
                        value = value.substring(0, value.length() - 1);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Не удалось прочитать " + key + FILE_SUFFIX, exception);
            }
        }
        if (value == null) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Не задан " + key + " или " + key + FILE_SUFFIX);
            }
            return defaultValue;
        }
        return value;
    }
}
