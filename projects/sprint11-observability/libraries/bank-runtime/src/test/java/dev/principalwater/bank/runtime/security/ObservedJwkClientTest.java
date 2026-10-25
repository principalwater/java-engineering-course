package dev.principalwater.bank.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.testing.JwtFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.web.client.HttpClientObservationsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

// Реальный decoder, реальный JWK Set по HTTP и настоящая автоконфигурация клиентов Boot: запрос
// ключей наблюдается, а тайм-аут собственной фабрики запросов остаётся в силе.
class ObservedJwkClientTest {
    private static final JwtFixture JWT = new JwtFixture();
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ObservationAutoConfiguration.class,
                                    MetricsAutoConfiguration.class,
                                    CompositeMeterRegistryAutoConfiguration.class,
                                    SimpleMetricsExportAutoConfiguration.class,
                                    HttpClientObservationsAutoConfiguration.class,
                                    HttpMessageConvertersAutoConfiguration.class,
                                    RestTemplateAutoConfiguration.class))
                    .withUserConfiguration(ApiJwtConfiguration.class);

    @Test
    void jwkSetRetrievalIsObservedAsAClientRequest() {
        runner.withPropertyValues(
                        "bank.security.issuer=" + JWT.issuer(),
                        "bank.security.jwk-set-uri=" + JWT.jwks())
                .run(
                        context -> {
                            var jwt =
                                    context.getBean(JwtDecoder.class)
                                            .decode(JWT.user("alice", "alice", "accounts.read"));

                            assertThat(jwt.getSubject()).isEqualTo("alice");
                            assertThat(
                                            context.getBean(MeterRegistry.class)
                                                    .get("http.client.requests")
                                                    .tag("outcome", "SUCCESS")
                                                    .timer()
                                                    .count())
                                    .isEqualTo(1);
                        });
    }

    @Test
    void silentJwkEndpointStillFailsWithinTheConfiguredReadTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.createContext(
                "/jwks",
                exchange -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        ExecutorService workers = Executors.newCachedThreadPool();
        silent.setExecutor(workers);
        silent.start();
        try {
            runner.withPropertyValues(
                            "bank.security.issuer=" + JWT.issuer(),
                            "bank.security.jwk-set-uri=http://127.0.0.1:"
                                    + silent.getAddress().getPort()
                                    + "/jwks")
                    .run(
                            context -> {
                                JwtDecoder decoder = context.getBean(JwtDecoder.class);
                                String token = JWT.user("alice", "alice", "accounts.read");
                                long started = System.nanoTime();

                                assertThatThrownBy(() -> decoder.decode(token))
                                        .isInstanceOf(JwtException.class);

                                // Настроенный тайм-аут чтения — 3 с: быстрее — отказ без ожидания,
                                // дольше — тайм-аут потерян при замене RestTemplate на builder
                                // Boot.
                                assertThat(Duration.ofNanos(System.nanoTime() - started))
                                        .isBetween(Duration.ofSeconds(2), Duration.ofSeconds(9));
                            });
        } finally {
            release.countDown();
            silent.stop(0);
            workers.shutdownNow();
        }
    }
}
