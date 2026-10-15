package dev.principalwater.store.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(-200)
public final class ResourceAdmissionFilter implements WebFilter {
    private final AtomicInteger active = new AtomicInteger();
    private final int limit;
    public ResourceAdmissionFilter(RuntimeProperties properties, MeterRegistry metrics) {
        limit = properties.maxInFlight();
        metrics.gauge("store.http.active", active);
    }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            // Проверка здоровья остаётся доступной при перегрузке; бизнес-запросы не копятся в памяти.
            if (exchange.getRequest().getPath().value().startsWith("/actuator/health")) { return chain.filter(exchange); }
            if (active.incrementAndGet() > limit) {
                active.decrementAndGet();
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                exchange.getResponse().getHeaders().set("Retry-After", "1");
                return exchange.getResponse().setComplete();
            }
            return Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> active.decrementAndGet());
        });
    }
}
