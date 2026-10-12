package dev.principalwater.blog.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("blog.cors")
public record CorsProperties(List<String> origins, Duration maxAge) {
    public CorsProperties {
        if (origins == null || origins.isEmpty()
                || origins.stream().anyMatch(origin -> origin == null || origin.isBlank() || origin.contains("*"))) {
            throw new IllegalArgumentException("CORS origins must contain exact, nonempty addresses without wildcards");
        }
        if (maxAge == null || maxAge.isNegative()) {
            throw new IllegalArgumentException("CORS max age must not be negative");
        }
        origins = List.copyOf(origins);
    }
}
