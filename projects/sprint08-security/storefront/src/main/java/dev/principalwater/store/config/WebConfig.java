package dev.principalwater.store.config;

import dev.principalwater.store.util.StorageFailures;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DisplayProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WebConfig implements WebFluxConfigurer {
    // Штатный обработчик Boot имеет order -1: преобразуем ошибку до попытки нарисовать error page через сессию.
    private static final int BEFORE_BOOT_ERROR_HANDLER = -2;
    @Bean
    @Order(BEFORE_BOOT_ERROR_HANDLER)
    public WebExceptionHandler payloadLimits() {
        // Multipart разбирается также в CSRF-фильтре, до controller advice; сохраняем одинаковый 413.
        return (exchange, failure) -> Mono.error(failure instanceof DataBufferLimitException
                ? new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request payload exceeds the import limit", failure)
                : failure);
    }

    // Хранилище сессий и проверка эпохи работают в фильтрах, вне controller advice: недоступность — 503, а не 500.
    // Ответ пишется напрямую: страница ошибки на Thymeleaf берёт сессию из того же упавшего запроса и сама не отрисуется.
    @Bean
    @Order(BEFORE_BOOT_ERROR_HANDLER)
    public WebExceptionHandler storageUnavailable() {
        return (exchange, failure) -> {
            var response = exchange.getResponse();
            if (!StorageFailures.isUnavailable(failure) || response.isCommitted()) {
                return Mono.error(failure);
            }
            // Менеджер сессий добавляет cookie до записи в хранилище: сессия, которой нет в Redis, клиенту не выдаётся.
            response.getCookies().clear();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            return response.setComplete().onErrorResume(StorageFailures::isUnavailable, commitFailure -> {
                if (response.isCommitted()) {
                    return Mono.error(commitFailure);
                }
                // beforeCommit save мог добавить cookie и упасть; Spring завершает COMMIT_ACTION_FAILED без повтора save.
                response.getCookies().clear();
                return response.setComplete();
            });
        };
    }
}
