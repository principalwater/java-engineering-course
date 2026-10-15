package dev.principalwater.store.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.model.StoreLimits;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

@Component
public class RedisCatalogPageCache extends AbstractJsonRedisCache<CatalogSnapshot> {
    private final int slots;

    public RedisCatalogPageCache(ReactiveRedisOperations<String, byte[]> redis, ObjectMapper json,
                                 CatalogCacheProperties properties, @Qualifier("cacheCodecScheduler") Scheduler scheduler,
                                 MeterRegistry metrics) {
        super(redis, json, properties, scheduler, metrics, "page", properties.maxEntryBytes(),
                CatalogSnapshot.class);
        slots = properties.pageSlots();
    }

    @Override
    protected String keySuffix(String logicalKey) {
        // Число поисковых запросов не ограничено каталогом; identity в JSON защищает от коллизий слотов.
        return Integer.toString(Math.floorMod(logicalKey.hashCode(), slots));
    }

    @Override
    protected boolean valid(String logicalKey, CatalogSnapshot page) {
        return page.products().size() <= StoreLimits.PAGE_SIZES.getLast()
                && page.products().stream().allMatch(RedisProductCache::validProduct);
    }
}
