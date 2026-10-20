package dev.principalwater.bank.notifications.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bank.probes.enabled", havingValue = "true")
public class WorkerAvailability {
    private final Path directory;

    public WorkerAvailability(@Value("${bank.probes.directory:/tmp/bank-probes}") Path directory)
            throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve("live"));
        Files.deleteIfExists(directory.resolve("ready"));
    }

    @EventListener
    public void availability(AvailabilityChangeEvent<?> event) {
        // Тот же lifecycle Boot без HTTP: отказ Kafka не означает поломку JVM.
        if (event.getState() instanceof LivenessState state) {
            update("live", state == LivenessState.CORRECT);
        } else if (event.getState() instanceof ReadinessState state) {
            update("ready", state == ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    private void update(String name, boolean available) {
        try {
            Path marker = directory.resolve(name);
            if (available) {
                Files.writeString(marker, "");
            } else {
                Files.deleteIfExists(marker);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot update worker availability", exception);
        }
    }
}
