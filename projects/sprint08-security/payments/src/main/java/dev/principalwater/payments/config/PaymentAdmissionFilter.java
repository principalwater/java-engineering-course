package dev.principalwater.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.payments.controller.PaymentProblems;
import dev.principalwater.payments.model.PaymentError;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentAdmissionFilter implements WebFilter {
    private static final int MAX_IN_FLIGHT = 64;
    private final AtomicInteger active = new AtomicInteger();
    private final Counter rejected;
    private final ObjectMapper json;

    public PaymentAdmissionFilter(MeterRegistry metrics, ObjectMapper json) {
        this.json = json;
        rejected = metrics.counter("payments.http.rejected");
        Gauge.builder("payments.http.inflight", active, AtomicInteger::get).register(metrics);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/actuator/health")) {
            return chain.filter(exchange);
        }
        return Mono.defer(() -> {
            if (active.incrementAndGet() > MAX_IN_FLIGHT) {
                active.decrementAndGet();
                rejected.increment();
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
                return PaymentProblems.write(PaymentError.UNAVAILABLE.getStatus(), PaymentError.UNAVAILABLE.getDetail(), exchange, json);
            }
            // Lease освобождается и при cancel: отключившийся HTTP-клиент не занимает admission навсегда.
            return Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> active.decrementAndGet());
        });
    }
}
