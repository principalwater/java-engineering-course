package dev.principalwater.store.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogCacheProperties.class)
public class CatalogCacheConfig {
    @Bean
    public ReactiveRedisOperations<String, byte[]> catalogRedis(ReactiveRedisConnectionFactory connections) {
        var serialization = RedisSerializationContext.<String, byte[]>newSerializationContext(RedisSerializer.string())
                .value(RedisSerializer.byteArray()).build();
        return new ReactiveRedisTemplate<>(connections, serialization);
    }
}
