package dev.principalwater.bank.observability;

import static org.assertj.core.api.Assertions.assertThat;

import brave.handler.MutableSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class DeferredTraceTest {
    private static final String RELAY = "bank.outbox.relay";

    @Test
    void relayContinuesCapturedTraceAfterRequestSpanAndThreadAreGone() throws Exception {
        TracingFixture.runner()
                .run(
                        context -> {
                            Tracer tracer = context.getBean(Tracer.class);
                            DeferredTrace deferred = context.getBean(DeferredTrace.class);
                            Span request = tracer.nextSpan().name("request").start();
                            DeferredTrace.Stored stored;
                            Tracer.SpanInScope scope = tracer.withSpan(request);
                            try {
                                stored = deferred.capture();
                            } finally {
                                scope.close();
                            }
                            request.end();

                            ExecutorService pool = Executors.newSingleThreadExecutor();
                            try {
                                String duringRelay =
                                        pool.submit(
                                                        () -> {
                                                            try (var relay =
                                                                    deferred.resume(
                                                                            stored,
                                                                            RELAY,
                                                                            "accounts.notifications")) {
                                                                relay.error(
                                                                        new IllegalStateException(
                                                                                "broker down"));
                                                                return MDC.get("traceId");
                                                            }
                                                        })
                                                .get(5, TimeUnit.SECONDS);
                                // Следующая задача на том же потоке не должна видеть контекст
                                // прошлой.
                                List<String> afterRelay =
                                        pool.submit(
                                                        () ->
                                                                List.of(
                                                                        String.valueOf(
                                                                                tracer
                                                                                        .currentSpan()),
                                                                        String.valueOf(
                                                                                MDC.get(
                                                                                        "traceId"))))
                                                .get(5, TimeUnit.SECONDS);

                                MutableSpan relaySpan = finished(RELAY);
                                MutableSpan requestSpan = finished("request");
                                assertThat(relaySpan.traceId()).isEqualTo(requestSpan.traceId());
                                assertThat(relaySpan.parentId()).isEqualTo(requestSpan.id());
                                assertThat(relaySpan.kind()).isEqualTo(brave.Span.Kind.CONSUMER);
                                assertThat(relaySpan.tag("topic"))
                                        .isEqualTo("accounts.notifications");
                                assertThat(relaySpan.error()).hasMessage("broker down");
                                assertThat(duringRelay).isEqualTo(relaySpan.traceId());
                                assertThat(afterRelay).containsExactly("null", "null");
                            } finally {
                                pool.shutdownNow();
                            }
                        });
    }

    @Test
    void captureWithoutActiveSpanStoresNothing() {
        TracingFixture.runner()
                .run(
                        context ->
                                assertThat(context.getBean(DeferredTrace.class).capture())
                                        .isEqualTo(DeferredTrace.Stored.NONE));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                "",
                "garbage",
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01",
                "00-00000000000000000000000000000000-0000000000000000-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra"
            })
    void corruptStoredParentStartsFreshTraceInsteadOfFailingPublication(String traceparent) {
        TracingFixture.runner()
                .run(
                        context -> {
                            DeferredTrace deferred = context.getBean(DeferredTrace.class);
                            try (var relay =
                                    deferred.resume(
                                            new DeferredTrace.Stored(traceparent, "vendor=value"),
                                            RELAY,
                                            "topic")) {
                                relay.error(new IllegalStateException("ignored"));
                            }
                            MutableSpan relaySpan = finished(RELAY);
                            assertThat(relaySpan.parentId()).isNull();
                            assertThat(relaySpan.traceId()).doesNotContain("4bf92f3577b34da6");
                        });
    }

    @Test
    void oversizedTracestateIsDroppedButParentIsKept() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        DeferredTrace.Stored stored =
                new DeferredTrace.Stored(
                        traceparent, "a=".concat("x".repeat(DeferredTrace.TRACESTATE_MAX_LENGTH)));
        assertThat(stored.traceparent()).isEqualTo(traceparent);
        assertThat(stored.tracestate()).isNull();
    }

    private static MutableSpan finished(String name) {
        List<MutableSpan> matches =
                TracingFixture.FINISHED.stream().filter(span -> name.equals(span.name())).toList();
        assertThat(matches).as("finished spans named %s", name).hasSize(1);
        return matches.getFirst();
    }
}
