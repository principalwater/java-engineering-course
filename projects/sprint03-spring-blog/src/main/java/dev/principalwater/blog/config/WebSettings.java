package dev.principalwater.blog.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;

public final class WebSettings {
    public static final String API_PATH_PATTERN = "/api/**";
    private static final String ORIGINS_PROPERTY = "CORS_ORIGINS";
    private static final String MAX_AGE_PROPERTY = "CORS_MAX_AGE_SECONDS";
    private static final String ORIGIN_SEPARATOR = ",";
    private static final String WILDCARD = "*";
    private static final List<HttpMethod> ALLOWED_METHODS = List.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS);

    private WebSettings() {
    }

    public static String[] allowedMethods() {
        return ALLOWED_METHODS.stream().map(HttpMethod::name).toArray(String[]::new);
    }

    public static String[] allowedOrigins(Environment environment) {
        String[] origins = Arrays.stream(environment.getRequiredProperty(ORIGINS_PROPERTY).split(ORIGIN_SEPARATOR))
                .map(String::strip).filter(origin -> !origin.isEmpty()).toArray(String[]::new);
        if (Arrays.stream(origins).anyMatch(origin -> origin.contains(WILDCARD))) {
            throw new IllegalArgumentException(ORIGINS_PROPERTY + " должен содержать точные адреса источников");
        }
        return origins;
    }

    public static long maxAgeSeconds(Environment environment) {
        long seconds = environment.getRequiredProperty(MAX_AGE_PROPERTY, Long.class);
        if (seconds < 0) {
            throw new IllegalArgumentException(MAX_AGE_PROPERTY + " не может быть отрицательным");
        }
        return seconds;
    }
}
