package dev.principalwater.store.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.StoreLimits;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

@Component
public class RedisProductCache extends AbstractJsonRedisCache<Product> {
    private static final int MAX_PRODUCT_BYTES = 32 * 1024;

    public RedisProductCache(ReactiveRedisOperations<String, byte[]> redis, ObjectMapper json,
                             CatalogCacheProperties properties, @Qualifier("cacheCodecScheduler") Scheduler scheduler,
                             MeterRegistry metrics) {
        super(redis, json, properties, scheduler, metrics, "product", properties.productSlots(), MAX_PRODUCT_BYTES, Product.class);
    }

    @Override
    protected boolean valid(String logicalKey, Product product) {
        return validProduct(product) && logicalKey.equals(product.id().toString());
    }

    static boolean validProduct(Product product) {
        return product != null && product.id() != null && product.id() > 0 && product.version() != null
                && product.version() >= 0 && product.image() != null && product.price() >= 1
                && product.price() <= StoreLimits.MAX_PRICE_RUBLES
                && validText(product.sku(), StoreLimits.SKU_LENGTH) && validText(product.title(), StoreLimits.TITLE_LENGTH)
                && validText(product.description(), StoreLimits.DESCRIPTION_LENGTH);
    }

    private static boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }
}
