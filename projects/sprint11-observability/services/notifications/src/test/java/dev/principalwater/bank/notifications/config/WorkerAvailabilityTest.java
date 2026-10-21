package dev.principalwater.bank.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;

class WorkerAvailabilityTest {
    @TempDir Path directory;

    @Test
    void markersFollowBootAvailabilityAndNeverReusePreviousProcessReadiness() throws IOException {
        Path live = directory.resolve("live");
        Path ready = directory.resolve("ready");
        Files.writeString(live, "");
        Files.writeString(ready, "");
        WorkerAvailability worker = new WorkerAvailability(directory);
        assertThat(live).doesNotExist();
        assertThat(ready).doesNotExist();
        worker.availability(new AvailabilityChangeEvent<>(this, LivenessState.CORRECT));
        assertThat(live).exists();
        assertThat(ready).doesNotExist();
        worker.availability(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        assertThat(ready).exists();
        worker.availability(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        assertThat(ready).doesNotExist();
        assertThat(live).exists();
        worker.availability(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
        assertThat(live).doesNotExist();
    }
}
