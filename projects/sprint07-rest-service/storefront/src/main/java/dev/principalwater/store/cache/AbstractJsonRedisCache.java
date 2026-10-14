package dev.principalwater.store.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.principalwater.store.model.StoreIdentity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public abstract class AbstractJsonRedisCache<T> {
    private static final String FORMAT_VERSION = "v1";
    private static final int WRITE_CONCURRENCY = 4;
    private final ReactiveRedisOperations<String, byte[]> redis;
    private final CatalogCacheProperties properties;
    private final Scheduler codecScheduler;
    private final String kind;
    private final int slots;
    private final int maxEntryBytes;
    private final ObjectReader reader;
    private final ObjectWriter writer;
    private final Counter hits;
    private final Counter misses;
    private final Counter failures;
    private final Counter skipped;

    protected AbstractJsonRedisCache(ReactiveRedisOperations<String, byte[]> redis, ObjectMapper json,
                                     CatalogCacheProperties properties, Scheduler codecScheduler,
                                     MeterRegistry metrics, String kind, int slots, int maxEntryBytes, Class<T> type) {
        this.redis = redis;
        this.properties = properties;
        this.codecScheduler = codecScheduler;
        this.kind = kind;
        this.slots = slots;
        this.maxEntryBytes = Math.min(properties.maxEntryBytes(), maxEntryBytes);
        var entryType = json.getTypeFactory().constructParametricType(Entry.class, type);
        reader = json.readerFor(entryType).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        writer = json.writerFor(entryType);
        hits = counter(metrics, "hit");
        misses = counter(metrics, "miss");
        failures = counter(metrics, "failure");
        skipped = counter(metrics, "skipped");
    }

    public Mono<T> get(StoreIdentity identity, String logicalKey) {
        return getAll(identity, List.of(logicalKey)).flatMap(found -> Mono.justOrEmpty(found.get(logicalKey)));
    }

    public Mono<Map<String, T>> getAll(StoreIdentity identity, List<String> logicalKeys) {
        if (!properties.enabled() || logicalKeys.isEmpty()) {
            return Mono.just(Map.of());
        }
        var keys = logicalKeys.stream().map(key -> key(identity, key)).toList();
        return Mono.defer(() -> redis.opsForValue().multiGet(keys))
                .flatMap(values -> Mono.fromCallable(() -> decode(identity, logicalKeys, values)).subscribeOn(codecScheduler))
                .timeout(properties.operationTimeout()).doOnNext(found -> {
                    hits.increment(found.size());
                    misses.increment(logicalKeys.size() - found.size());
                }).onErrorResume(failure -> {
                    failures.increment();
                    misses.increment(logicalKeys.size());
                    // Кеш необязателен: SQL loader вызывается снаружи и его ошибки здесь не перехватываются.
                    return Mono.just(Map.of());
                });
    }

    public Mono<Void> put(StoreIdentity identity, String logicalKey, T value) {
        if (!properties.enabled()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> writer.writeValueAsBytes(new Entry<>(logicalKey, identity.catalogRevision(), value)))
                .subscribeOn(codecScheduler).flatMap(bytes -> {
                    if (bytes.length > maxEntryBytes) {
                        skipped.increment();
                        return Mono.empty();
                    }
                    return redis.opsForValue().set(key(identity, logicalKey), bytes, properties.ttl()).then();
                }).timeout(properties.operationTimeout()).onErrorResume(failure -> {
                    failures.increment();
                    return Mono.empty();
                });
    }

    public Mono<Void> putAll(StoreIdentity identity, Map<String, T> values) {
        if (!properties.enabled() || values.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(values.entrySet())
                .flatMap(entry -> put(identity, entry.getKey(), entry.getValue()), WRITE_CONCURRENCY).then()
                // Deadline относится ко всему batch, а не складывается по четырём параллельным SET.
                .timeout(properties.operationTimeout()).onErrorResume(failure -> {
                    failures.increment();
                    return Mono.empty();
                });
    }

    protected abstract boolean valid(String logicalKey, T value);

    private Map<String, T> decode(StoreIdentity identity, List<String> keys, List<byte[]> values) {
        var found = new LinkedHashMap<String, T>();
        for (int index = 0; index < keys.size(); index++) {
            byte[] bytes = values.get(index);
            if (bytes == null) {
                continue;
            }
            if (bytes.length > maxEntryBytes) {
                skipped.increment();
                continue;
            }
            try {
                Entry<T> entry = reader.readValue(bytes);
                if (entry != null && keys.get(index).equals(entry.identity())
                        && identity.catalogRevision() == entry.revision() && entry.value() != null
                        && valid(keys.get(index), entry.value())) {
                    found.put(keys.get(index), entry.value());
                }
            } catch (IOException | IllegalArgumentException failure) {
                failures.increment();
            }
        }
        return Map.copyOf(found);
    }

    private String key(StoreIdentity identity, String logicalKey) {
        // Фиксированное число слотов ограничивает keyspace; полная identity отличает безопасный miss при коллизии.
        return properties.namespace() + ":" + FORMAT_VERSION + ":" + identity.sourceId() + ":" + kind
                + ":" + Math.floorMod(logicalKey.hashCode(), slots);
    }

    private Counter counter(MeterRegistry metrics, String outcome) {
        return metrics.counter("store.cache.entries", "kind", kind, "outcome", outcome);
    }

    private record Entry<T>(String identity, long revision, T value) {
    }
}
