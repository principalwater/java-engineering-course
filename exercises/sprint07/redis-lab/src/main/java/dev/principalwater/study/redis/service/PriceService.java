package dev.principalwater.study.redis.service;

import java.math.BigDecimal;
import java.util.function.Supplier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;

@Service
public class PriceService implements IPriceService {
    private static final String PRICES = "prices";
    private static final String CAR_PRICES = "car-prices";

    @Override
    @Cacheable(cacheNames = PRICES, key = "#name")
    public BigDecimal computePrice(String name, Supplier<BigDecimal> price) {
        return price.get();
    }

    @Override
    @CacheEvict(cacheNames = PRICES, allEntries = true)
    public void clearPricesCache() {}

    @Override
    @CacheEvict(cacheNames = PRICES, key = "#name")
    public void evictPriceFromCache(String name) {}

    @Override
    @CachePut(cacheNames = CAR_PRICES, key = "#name")
    public BigDecimal upsertPriceInCache(String name, BigDecimal carPrice) {
        // По контракту этот метод изменяет car-prices, остальные методы работают с prices.
        return carPrice;
    }
}
