package dev.principalwater.study.redis.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

@RedisHash(value = "study-notifications", timeToLive = 1)
public record MessageNotification(@Id String sender, @Indexed String receiver, String content) {}
