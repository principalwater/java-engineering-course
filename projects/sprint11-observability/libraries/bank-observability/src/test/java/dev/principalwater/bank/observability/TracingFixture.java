package dev.principalwater.bank.observability;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.BraveAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Настоящий стек Boot (Brave, W3C, ObservationRegistry) с перехватом завершённых span: тесты
// проверяют родителей и trace id так, как их увидел бы Zipkin, без самодельного Tracer.
final class TracingFixture {
    static final List<MutableSpan> FINISHED = new CopyOnWriteArrayList<>();

    private TracingFixture() {}

    static ApplicationContextRunner runner() {
        FINISHED.clear();
        return new ApplicationContextRunner()
                .withPropertyValues(
                        "management.tracing.sampling.probability=1.0",
                        "management.tracing.propagation.produce=W3C",
                        "management.tracing.propagation.consume=W3C")
                .withConfiguration(
                        AutoConfigurations.of(
                                ObservationAutoConfiguration.class,
                                MicrometerTracingAutoConfiguration.class,
                                BraveAutoConfiguration.class,
                                BankObservabilityAutoConfiguration.class))
                .withUserConfiguration(Capture.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        SpanHandler capturedSpans() {
            return new SpanHandler() {
                @Override
                public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                    FINISHED.add(span);
                    return true;
                }
            };
        }
    }
}
