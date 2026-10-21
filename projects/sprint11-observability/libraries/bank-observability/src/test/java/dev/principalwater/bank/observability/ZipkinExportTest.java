package dev.principalwater.bank.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.BraveAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.zipkin.ZipkinAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// Non-web контекст, как у Notifications: экспорт включается только свойством и работает без
// web-зависимостей (JDK HttpClient в отправителе Boot).
class ZipkinExportTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ObservationAutoConfiguration.class,
                                    MicrometerTracingAutoConfiguration.class,
                                    BraveAutoConfiguration.class,
                                    ZipkinAutoConfiguration.class))
                    .withPropertyValues(
                            "spring.config.import=classpath:bank-observability.properties",
                            "spring.application.name=fixture-worker");

    @Test
    void exportIsOffByDefaultEvenWhenAnEndpointExists() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer zipkin = fakeZipkin(bodies);
        try {
            runner.withPropertyValues(
                            "management.zipkin.tracing.endpoint=http://127.0.0.1:"
                                    + zipkin.getAddress().getPort()
                                    + "/api/v2/spans",
                            "management.tracing.sampling.probability=1.0")
                    .run(
                            context -> {
                                Span span =
                                        context.getBean(Tracer.class)
                                                .nextSpan()
                                                .name("worker-poll")
                                                .start();
                                span.end();
                                // Пакетный отправитель сбрасывает данные раз в секунду.
                                Thread.sleep(2500);
                                assertThat(bodies).isEmpty();
                            });
        } finally {
            zipkin.stop(0);
        }
    }

    @Test
    void enabledExportPostsFinishedSpansToTheConfiguredEndpoint() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer zipkin = fakeZipkin(bodies);
        try {
            runner.withPropertyValues(
                            "management.zipkin.tracing.export.enabled=true",
                            "management.zipkin.tracing.endpoint=http://127.0.0.1:"
                                    + zipkin.getAddress().getPort()
                                    + "/api/v2/spans",
                            "management.tracing.sampling.probability=1.0")
                    .run(
                            context -> {
                                Tracer tracer = context.getBean(Tracer.class);
                                Span span = tracer.nextSpan().name("worker-poll").start();
                                span.end();

                                await().atMost(Duration.ofSeconds(15))
                                        .untilAsserted(
                                                () ->
                                                        assertThat(String.join("", bodies))
                                                                .contains("worker-poll")
                                                                .contains("fixture-worker"));
                            });
        } finally {
            zipkin.stop(0);
        }
    }

    private static HttpServer fakeZipkin(List<String> bodies) throws Exception {
        HttpServer zipkin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        zipkin.createContext(
                "/api/v2/spans",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                });
        zipkin.start();
        return zipkin;
    }
}
