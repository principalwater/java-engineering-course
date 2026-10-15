package dev.principalwater.store.runtime;

import dev.principalwater.store.ReactiveTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceAdmissionFilterTest extends ReactiveTestBase {
    @Test
    void overloadRejectsImmediatelyAndCancellationReleasesTheLease() {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new ResourceAdmissionFilter(new RuntimeProperties(2, 1, 32, 256, 8), metrics);
            var leases = new ArrayList<Disposable>();
            try {
                for (int index = 0; index < 8; index++) {
                    leases.add(filter.filter(exchange("/items"), ignored -> Mono.never()).subscribe());
                }
                var rejected = exchange("/items");
                filter.filter(rejected, ignored -> Mono.error(new AssertionError("Overload reached the handler"))).block(TIMEOUT);
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getResponse().getStatusCode());
                assertEquals("1", rejected.getResponse().getHeaders().getFirst("Retry-After"));
                filter.filter(exchange("/actuator/health/readiness"), ignored -> Mono.empty()).block(TIMEOUT);
                leases.getFirst().dispose();
                var admitted = exchange("/items");
                filter.filter(admitted, ignored -> Mono.empty()).block(TIMEOUT);
                assertNull(admitted.getResponse().getStatusCode());
            } finally { leases.forEach(Disposable::dispose); }
            assertEquals(0, metrics.get("store.http.active").gauge().value());
        } finally { metrics.close(); }
    }
    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }
}
