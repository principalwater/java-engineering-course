package dev.principalwater.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentAdmissionFilterTest {
    @Test
    void saturationRejectsNewWorkAndCancellationImmediatelyReleasesItsLease() {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new PaymentAdmissionFilter(metrics, new ObjectMapper().findAndRegisterModules());
            var pending = IntStream.range(0, 64).mapToObj(index -> filter.filter(exchange(), request -> Mono.never()).subscribe()).toList();
            try {
                var rejected = exchange();
                var entered = new AtomicBoolean();
                filter.filter(rejected, request -> {
                    entered.set(true);
                    return Mono.empty();
                }).block(Duration.ofSeconds(1));
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getResponse().getStatusCode());
                assertEquals("1", rejected.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                assertFalse(entered.get());
                pending.getFirst().dispose();
                filter.filter(exchange(), request -> {
                    entered.set(true);
                    return Mono.empty();
                }).block(Duration.ofSeconds(1));
                assertTrue(entered.get());
            } finally {
                pending.forEach(Disposable::dispose);
            }
            assertEquals(0, metrics.get("payments.http.inflight").gauge().value());
        } finally {
            metrics.close();
        }
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/payments").build());
    }
}
