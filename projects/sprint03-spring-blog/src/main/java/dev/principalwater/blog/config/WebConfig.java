package dev.principalwater.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@PropertySource("classpath:blog.properties")
@EnableWebMvc
@ComponentScan("dev.principalwater.blog.controller")
public class WebConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    private final long maxAgeSeconds;

    public WebConfig(Environment environment) {
        allowedOrigins = WebSettings.allowedOrigins(environment);
        maxAgeSeconds = WebSettings.maxAgeSeconds(environment);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(WebSettings.API_PATH_PATTERN).allowedOrigins(allowedOrigins)
                .allowedMethods(WebSettings.allowedMethods())
                .allowedHeaders(HttpHeaders.CONTENT_TYPE).maxAge(maxAgeSeconds);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("/")
                .setCacheControl(CacheControl.noCache());
    }

    @Bean
    public StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }
}
