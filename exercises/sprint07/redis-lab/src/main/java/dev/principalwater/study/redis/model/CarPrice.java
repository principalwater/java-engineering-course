package dev.principalwater.study.redis.model;

import java.math.BigDecimal;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@RedisHash(value = CarPrice.KEYSPACE, timeToLive = CarPrice.TTL_SECONDS)
public record CarPrice(@Id String name, BigDecimal price) {
    // Spring Data Redis 3.4 разбирает expiration key по первому ':': keyspace не содержит этот разделитель.
    public static final String KEYSPACE = "study-car-prices";
    public static final long TTL_SECONDS = 10;
}
