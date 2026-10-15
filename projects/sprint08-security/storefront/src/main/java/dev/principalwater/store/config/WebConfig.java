package dev.principalwater.store.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DisplayProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WebConfig implements WebFluxConfigurer {
    @Bean
    @Order(-2)
    public WebExceptionHandler payloadLimits() {
        // Multipart разбирается также в CSRF-фильтре, до controller advice; сохраняем одинаковый 413.
        return (exchange, failure) -> Mono.error(failure instanceof DataBufferLimitException
                ? new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request payload exceeds the import limit", failure)
                : failure);
    }
}
