package dev.principalwater.study.redis.service;

import java.math.BigDecimal;
import java.util.function.Supplier;

public interface IPriceService {
    /** Читает prices или вычисляет цену и сохраняет результат. */
    BigDecimal computePrice(String name, Supplier<BigDecimal> price);

    /** Очищает весь prices. */
    void clearPricesCache();

    /** Удаляет одну цену из prices. */
    void evictPriceFromCache(String name);

    /** Сохраняет цену в отдельном car-prices. */
    BigDecimal upsertPriceInCache(String name, BigDecimal carPrice);
}
