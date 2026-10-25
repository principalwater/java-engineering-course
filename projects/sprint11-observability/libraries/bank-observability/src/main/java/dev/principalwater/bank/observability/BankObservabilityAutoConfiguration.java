package dev.principalwater.bank.observability;

import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

// Подключается через AutoConfiguration.imports: сервисы не сканируют пакет observability и не
// копируют конфигурацию.
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration"
        })
public class BankObservabilityAutoConfiguration {
    @Bean
    ObservationFilter bankHttpTracePrivacy() {
        return new HttpTracePrivacy();
    }

    @Bean
    ObservationPredicate bankJdbcChildObservations() {
        return new JdbcChildObservations();
    }

    @Bean
    DeferredTrace deferredTrace(
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator,
            ObservationRegistry observations) {
        // При выключенной трассировке (например, в тестах Boot) остаётся no-op: outbox не хранит
        // контекст, а наблюдения превращаются в обычные метрики.
        return new DeferredTrace(
                tracer.getIfAvailable(() -> Tracer.NOOP),
                propagator.getIfAvailable(() -> Propagator.NOOP),
                observations);
    }
}
