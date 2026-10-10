package dev.principalwater.blog.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {
    private static final String API_PATH_PATTERN = "/api/**";
    private static final List<HttpMethod> ALLOWED_METHODS = List.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS);
    private final CorsProperties cors;

    public WebConfig(CorsProperties cors) {
        this.cors = cors;
    }

    // Без @EnableWebMvc: расширяем MVC, сохраняя автоконфигурацию Boot и обработку multipart.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(API_PATH_PATTERN)
                .allowedOrigins(cors.origins().toArray(String[]::new))
                .allowedMethods(ALLOWED_METHODS.stream().map(HttpMethod::name).toArray(String[]::new))
                .allowedHeaders(HttpHeaders.CONTENT_TYPE).maxAge(cors.maxAge().toSeconds());
    }
}
