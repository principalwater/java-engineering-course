package dev.principalwater.study.redis.service;

import java.time.Duration;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PopularArticleService implements PopularArticleAware {
    private static final Duration ARTICLE_TTL = Duration.ofDays(1);
    private static final String KEY_PREFIX = "study:article:";
    private final StringRedisTemplate strings;

    public PopularArticleService(StringRedisTemplate strings) {
        this.strings = strings;
    }

    @Override
    public void cache(String isbnNumber, String articleContent) {
        strings.opsForValue().set(key(isbnNumber), Objects.requireNonNull(articleContent, "Article content is required"), ARTICLE_TTL);
    }

    @Override
    public String getArticle(String isbnNumber) {
        // GETEX читает и продлевает TTL атомарно, не оставляя окна между GET и EXPIRE.
        return strings.opsForValue().getAndExpire(key(isbnNumber), ARTICLE_TTL);
    }

    private static String key(String isbnNumber) {
        if (isbnNumber == null || isbnNumber.isBlank()) {
            throw new IllegalArgumentException("Article identifier is required");
        }
        return KEY_PREFIX + isbnNumber;
    }
}
