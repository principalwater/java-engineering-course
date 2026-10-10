package dev.principalwater.study.redis;

import dev.principalwater.study.redis.model.CarPrice;
import dev.principalwater.study.redis.repository.CarPriceRepository;
import java.math.BigDecimal;
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

    public RedisLabChecks(CarPriceRepository cars, StringRedisTemplate strings) {
        this.cars = cars;
        this.strings = strings;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
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
}
