package dev.principalwater.store.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
// Ограничиваем нагрузку до чтения сессии и проверки пароля в Spring Security.
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ResourceAdmissionFilter implements WebFilter {
    // Два независимых слота для liveness/readiness: перегрузка магазина не скрывает здоровье и не обходит общий бюджет.
    private static final int HEALTH_RESERVE = 2;
    private static final String HEALTH_PATH = "/actuator/health";
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger probing = new AtomicInteger();
    private final int limit;
    public ResourceAdmissionFilter(RuntimeProperties properties, MeterRegistry metrics) {
        limit = properties.maxInFlight();
        metrics.gauge("store.http.active", active);
    }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            String path = exchange.getRequest().getPath().value();
            boolean health = path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/");
            AtomicInteger counter = health ? probing : active;
            int capacity = health ? HEALTH_RESERVE : limit;
            if (counter.incrementAndGet() > capacity) {
                counter.decrementAndGet();
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
                return exchange.getResponse().setComplete();
            }
            return Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> counter.decrementAndGet());
        });
    }
}
