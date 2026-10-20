package dev.principalwater.bank.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

// Переносит родителя трассы через долговременную границу (outbox): родитель сохраняется
// вместе с бизнес-фактом, а отложенная публикация продолжает ту же трассу, а не начинает
// случайную новую. Формат хранения — W3C Trace Context независимо от настроек wire-propagation.
public final class DeferredTrace {
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    // version-traceId-parentId-flags; жёсткий шаблон отсекает мусор из БД до передачи extractor.
    private static final Pattern TRACEPARENT_FORMAT =
            Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    // Совпадает с varchar(55) в миграции: длина traceparent версии 00 фиксирована спецификацией.
    public static final int TRACEPARENT_LENGTH = 55;
    // Спецификация разрешает отбрасывать tracestate; граница держит строку outbox компактной.
    public static final int TRACESTATE_MAX_LENGTH = 512;
    private static final DeferredTrace DISABLED =
            new DeferredTrace(Tracer.NOOP, Propagator.NOOP, ObservationRegistry.NOOP);
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObservationRegistry observations;

    public DeferredTrace(Tracer tracer, Propagator propagator, ObservationRegistry observations) {
        this.tracer = tracer;
        this.propagator = propagator;
        this.observations = observations;
    }

    // Экземпляр для окружений без трассировки: ничего не сохраняет и не создаёт наблюдений.
    public static DeferredTrace disabled() {
        return DISABLED;
    }

    // Родитель в виде, пригодном для хранения; NONE означает «нет активной трассы».
    public record Stored(String traceparent, String tracestate) {
        public static final Stored NONE = new Stored(null, null);

        // Значения приходят из БД, поэтому всё, что не похоже на W3C, превращается в отсутствие
        // родителя: публикация должна продолжиться даже при испорченной трассе.
        public Stored {
            if (traceparent == null
                    || traceparent.length() != TRACEPARENT_LENGTH
                    || !TRACEPARENT_FORMAT.matcher(traceparent).matches()) {
                traceparent = null;
                tracestate = null;
            } else if (tracestate != null
                    && (tracestate.isBlank() || tracestate.length() > TRACESTATE_MAX_LENGTH)) {
                tracestate = null;
            }
        }
    }

    // Фиксирует текущий span; вызывается внутри транзакции, которая создаёт бизнес-факт.
    public Stored capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return Stored.NONE;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return new Stored(carrier.get(TRACEPARENT), carrier.get(TRACESTATE));
    }

    // Открывает наблюдение-потомка сохранённого родителя; try-with-resources гарантирует, что
    // scope закрывается и в пуле потоков не остаётся контекста предыдущей задачи.
    public Relay resume(Stored parent, String name, String topic) {
        Map<String, String> carrier = new HashMap<>();
        if (parent.traceparent() != null) {
            carrier.put(TRACEPARENT, parent.traceparent());
        }
        if (parent.tracestate() != null) {
            carrier.put(TRACESTATE, parent.tracestate());
        }
        ReceiverContext<Map<String, String>> context =
                new ReceiverContext<>(Map::get, Kind.CONSUMER);
        context.setCarrier(carrier);
        Observation observation =
                Observation.createNotStarted(name, () -> context, observations)
                        .contextualName(name)
                        .lowCardinalityKeyValue("topic", topic)
                        .start();
        return new Relay(observation, observation.openScope());
    }

    public static final class Relay implements AutoCloseable {
        private final Observation observation;
        private final Observation.Scope scope;

        private Relay(Observation observation, Observation.Scope scope) {
            this.observation = observation;
            this.scope = scope;
        }

        // Неудачная публикация остаётся видимой в трассе и метрике, даже если вызывающий код
        // обработал исключение и отложил повтор.
        public void error(Throwable failure) {
            observation.error(failure);
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                observation.stop();
            }
        }
    }
}
