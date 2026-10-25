package dev.principalwater.bank.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.JvmMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PrometheusScrapeServerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    void servesOnlyGetOfTheScrapePathAndReleasesPortAndThreadsOnStop() throws Exception {
        registry.counter("bank_fixture_events", "kind", "fixture").increment(3);
        PrometheusScrapeServer server = new PrometheusScrapeServer(registry, "127.0.0.1", 0);
        server.start();
        int port = server.port();
        try {
            HttpResponse<String> scrape = send("GET", port, "/actuator/prometheus");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("text/plain; version=0.0.4");
            assertThat(scrape.body()).contains("bank_fixture_events_total{kind=\"fixture\"} 3.0");

            HttpResponse<String> write = send("POST", port, "/actuator/prometheus");
            assertThat(write.statusCode()).isEqualTo(405);
            assertThat(write.headers().firstValue("Allow")).hasValue("GET");
            // Здесь нет ни health, ни env, ни бизнес-маршрутов: все остальные пути — 404.
            for (String path :
                    List.of(
                            "/",
                            "/actuator",
                            "/actuator/env",
                            "/actuator/prometheus/extra",
                            "/api/accounts/me")) {
                assertThat(send("GET", port, path).statusCode()).as(path).isEqualTo(404);
            }
        } finally {
            server.stop();
        }

        assertThat(refused(port)).isTrue();
        await().atMost(TIMEOUT)
                .untilAsserted(
                        () ->
                                assertThat(Thread.getAllStackTraces().keySet())
                                        .noneMatch(
                                                thread ->
                                                        thread.getName()
                                                                .startsWith("bank-metrics-")));
        server.stop();
    }

    @Test
    void contextOwnsTheServerOnlyWhenEnabledAndStopsItWithTheContext() throws Exception {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        // Реестр создаёт штатная автоконфигурация Boot, как в Notifications.
                        .withConfiguration(
                                AutoConfigurations.of(
                                        MetricsAutoConfiguration.class,
                                        CompositeMeterRegistryAutoConfiguration.class,
                                        PrometheusMetricsExportAutoConfiguration.class,
                                        JvmMetricsAutoConfiguration.class,
                                        MetricsServerAutoConfiguration.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(PrometheusScrapeServer.class));

        int[] port = new int[1];
        runner.withPropertyValues(
                        "bank.observability.metrics-server.enabled=true",
                        "bank.observability.metrics-server.host=127.0.0.1",
                        "bank.observability.metrics-server.port=0")
                .run(
                        context -> {
                            port[0] = context.getBean(PrometheusScrapeServer.class).port();
                            HttpResponse<String> scrape =
                                    send("GET", port[0], "/actuator/prometheus");
                            assertThat(scrape.statusCode()).isEqualTo(200);
                            assertThat(scrape.body()).contains("jvm_memory_used_bytes");
                        });
        assertThat(refused(port[0])).isTrue();
    }

    private HttpResponse<String> send(String method, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .timeout(TIMEOUT)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private boolean refused(int port) throws Exception {
        try {
            send("GET", port, "/actuator/prometheus");
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (IOException other) {
            // Закрытое соединение keep-alive тоже означает, что сервер не отвечает.
            return true;
        }
    }
}
