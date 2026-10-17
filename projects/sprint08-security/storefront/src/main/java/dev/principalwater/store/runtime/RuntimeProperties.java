package dev.principalwater.store.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("store.runtime")
public record RuntimeProperties(
        // Два сетевых loop-потока перекрывают ожидание I/O; это не число параллельных вычислений.
        @DefaultValue("2") int ioWorkers,
        // JSON кеша вынесен с event loop; один CPU-поток не конкурирует с сетевыми задачами за весь CPU.
        @DefaultValue("1") int codecWorkers,
        // Очереди и число запросов ограничивают память небольшого локального стенда при замедлении зависимостей.
        @DefaultValue("32") int codecQueue,
        @DefaultValue("256") int redisQueue,
        @DefaultValue("64") int maxInFlight) {
    private static final int MAX_IO_WORKERS = 8;
    private static final int MAX_CODEC_WORKERS = 4;
    private static final int MAX_CODEC_QUEUE = 256;
    private static final int MAX_REDIS_QUEUE = 1024;
    private static final int MIN_IN_FLIGHT = 8;
    private static final int MAX_IN_FLIGHT = 512;

    public RuntimeProperties {
        if (ioWorkers < 1 || ioWorkers > MAX_IO_WORKERS || codecWorkers < 1 || codecWorkers > MAX_CODEC_WORKERS
                || codecQueue < 1 || codecQueue > MAX_CODEC_QUEUE || redisQueue < 1 || redisQueue > MAX_REDIS_QUEUE
                || maxInFlight < MIN_IN_FLIGHT || maxInFlight > MAX_IN_FLIGHT) {
            throw new IllegalArgumentException("Runtime worker or queue budget is outside the supported range");
        }
    }
}
