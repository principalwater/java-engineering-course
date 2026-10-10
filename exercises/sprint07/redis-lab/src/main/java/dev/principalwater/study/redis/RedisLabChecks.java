package dev.principalwater.study.redis;

import dev.principalwater.study.redis.model.CarPrice;
import dev.principalwater.study.redis.repository.CarPriceRepository;
import dev.principalwater.study.redis.service.PopularArticleAware;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisLabChecks implements ApplicationRunner {
    private static final long EXPIRATION_MARGIN_SECONDS = 2;
    private final CarPriceRepository cars;
    private final StringRedisTemplate strings;
    private final PopularArticleAware articles;

    public RedisLabChecks(CarPriceRepository cars, StringRedisTemplate strings, PopularArticleAware articles) {
        this.cars = cars;
        this.strings = strings;
        this.articles = articles;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        checkArticleCache();
        var car = new CarPrice("inspection-" + UUID.randomUUID(), new BigDecimal("100000.25"));
        cars.save(car);
        if (!cars.findById(car.name()).orElseThrow().equals(car)) {
            throw new IllegalStateException("Repository did not preserve the car price");
        }
        Long ttl = strings.getExpire(CarPrice.KEYSPACE + ":" + car.name(), TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0 || ttl > CarPrice.TTL_SECONDS) {
            throw new IllegalStateException("Repository did not assign the expected TTL");
        }
        System.out.println("Repository stored a car price with TTL=" + ttl + " seconds");
        TimeUnit.SECONDS.sleep(CarPrice.TTL_SECONDS + EXPIRATION_MARGIN_SECONDS);
        if (cars.findById(car.name()).isPresent()) {
            throw new IllegalStateException("Expired car is still readable");
        }
        for (CarPrice row : cars.findAll()) {
            if (row == null || row.name().equals(car.name())) {
                throw new IllegalStateException("Expired repository index was not cleaned up");
            }
        }
        System.out.println("Redis repository: round trip, expiration and index cleanup passed");
    }

    private void checkArticleCache() {
        String id = UUID.randomUUID().toString();
        String key = "study:article:" + id;
        long dailySeconds = Duration.ofDays(1).toSeconds();
        try {
            articles.cache(id, "Spring Data Redis: атомарное продление TTL");
            Long initialTtl = strings.getExpire(key);
            if (initialTtl == null || initialTtl < dailySeconds - EXPIRATION_MARGIN_SECONDS || initialTtl > dailySeconds) {
                throw new IllegalStateException("Article was not cached for one day");
            }
            strings.expire(key, Duration.ofSeconds(10));
            if (!"Spring Data Redis: атомарное продление TTL".equals(articles.getArticle(id))) {
                throw new IllegalStateException("Cached article did not round trip");
            }
            Long refreshedTtl = strings.getExpire(key);
            if (refreshedTtl == null || refreshedTtl < dailySeconds - EXPIRATION_MARGIN_SECONDS) {
                throw new IllegalStateException("Reading an article did not renew its TTL");
            }
            articles.cache(id, "Updated content");
            if (!"Updated content".equals(articles.getArticle(id))) {
                throw new IllegalStateException("Article update did not replace its content");
            }
            strings.delete(key);
            if (articles.getArticle(id) != null || Boolean.TRUE.equals(strings.hasKey(key))) {
                throw new IllegalStateException("Cache miss created a value");
            }
            System.out.println("Article cache: content, daily TTL, atomic refresh, update and miss passed");
        } finally {
            strings.delete(key);
        }
    }
}
