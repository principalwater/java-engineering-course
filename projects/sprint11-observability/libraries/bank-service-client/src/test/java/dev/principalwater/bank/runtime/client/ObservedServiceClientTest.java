package dev.principalwater.bank.runtime.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.testing.BankFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.web.client.HttpClientObservationsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

// Боевая сборка клиента с builder Boot: и запрос токена, и вызов сервиса наблюдаются, а OAuth
// client credentials работают как раньше.
class ObservedServiceClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    // Порт фикстуры читают конфигурации контекста, который создаётся внутри теста.
    private static volatile int port;

    @Test
    void tokenRequestAndServiceCallAreBothObservedClientRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    respond(
                            exchange,
                            "{\"access_token\":\"fixture-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}");
                });
        server.createContext(
                "/internal/accounts/operations",
                exchange -> {
                    InternalOperation operation =
                            MAPPER.readValue(exchange.getRequestBody(), InternalOperation.class);
                    respond(
                            exchange,
                            MAPPER.writeValueAsString(
                                    new OperationResult(
                                            operation.operationId(),
                                            OperationStatus.SUCCEEDED,
                                            null,
                                            UUID.randomUUID(),
                                            100L,
                                            Instant.parse("2026-10-17T12:00:00Z"))));
                });
        server.start();
        try {
            port = server.getAddress().getPort();
            new ApplicationContextRunner()
                    // SpringApplication добавляет это в боевом запуске; runner — нет, а @Value
                    // читает Duration из свойств.
                    .withInitializer(
                            context ->
                                    context.getBeanFactory()
                                            .setConversionService(
                                                    ApplicationConversionService
                                                            .getSharedInstance()))
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ObservationAutoConfiguration.class,
                                    MetricsAutoConfiguration.class,
                                    CompositeMeterRegistryAutoConfiguration.class,
                                    SimpleMetricsExportAutoConfiguration.class,
                                    HttpClientObservationsAutoConfiguration.class,
                                    JacksonAutoConfiguration.class,
                                    HttpMessageConvertersAutoConfiguration.class,
                                    RestClientAutoConfiguration.class))
                    .withUserConfiguration(ServiceClientConfiguration.class, Collaborators.class)
                    .withPropertyValues("bank.client.enabled=true")
                    .run(
                            context -> {
                                InternalOperation command =
                                        BankFixtures.deposit(BankFixtures.actor("alice"), 100);

                                OperationResult result =
                                        context.getBean(ServiceClient.class)
                                                .post(
                                                        "accounts",
                                                        "/internal/accounts/operations",
                                                        command,
                                                        OperationResult.class);

                                assertThat(result.operationId()).isEqualTo(command.operationId());
                                long observed =
                                        context
                                                .getBean(MeterRegistry.class)
                                                .find("http.client.requests")
                                                .timers()
                                                .stream()
                                                .mapToLong(Timer::count)
                                                .sum();
                                assertThat(observed).isEqualTo(2);
                            });
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Collaborators {
        @Bean
        ObjectMapper mapper() {
            return MAPPER;
        }

        @Bean
        ClientRegistrationRepository registrations() {
            return new InMemoryClientRegistrationRepository(
                    ClientRegistration.withRegistrationId("service")
                            .clientId("fixture-cash-service")
                            .clientSecret("fixture-secret")
                            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                            .tokenUri("http://127.0.0.1:" + port + "/token")
                            .scope("accounts.cash")
                            .build());
        }

        @Bean
        OAuth2AuthorizedClientService authorizedClients(
                ClientRegistrationRepository registrations) {
            return new InMemoryOAuth2AuthorizedClientService(registrations);
        }

        @Bean
        DiscoveryClient discovery() {
            DiscoveryClient discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("accounts"))
                    .thenReturn(
                            List.of(
                                    new DefaultServiceInstance(
                                            "fixture-accounts",
                                            "accounts",
                                            "127.0.0.1",
                                            port,
                                            false)));
            return discovery;
        }
    }
}
