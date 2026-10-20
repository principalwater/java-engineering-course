package dev.principalwater.bank.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.contract.ProbePaths;
import dev.principalwater.bank.testing.JwtFixture;
import dev.principalwater.bank.testing.SecurityTestBase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayIntegrationTest extends SecurityTestBase {
    private static final AtomicReference<String> RECEIVED_TOKEN = new AtomicReference<>();
    private static final AtomicBoolean RECEIVED_OWNER_HEADER = new AtomicBoolean();
    private static final HttpServer ACCOUNTS = accountsServer();
    @Autowired private WebTestClient client;
    @Autowired private ConfigurableApplicationContext context;

    @Test
    void probesReportReadinessWithoutCredentialsOrDependencyDetails() {
        client.get()
                .uri(ProbePaths.LIVENESS)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("{\"status\":\"UP\"}");
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            client.get()
                    .uri(ProbePaths.READINESS)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .json("{\"status\":\"OUT_OF_SERVICE\"}");
            client.get().uri(ProbePaths.LIVENESS).exchange().expectStatus().isOk();
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        client.get().uri(ProbePaths.READINESS).exchange().expectStatus().isOk();
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    @DynamicPropertySource
    static void discoveredAccount(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.cloud.discovery.client.simple.instances.accounts[0].uri",
                () -> "http://127.0.0.1:" + ACCOUNTS.getAddress().getPort());
    }

    @Test
    void forwardsActualUserJwtAndStripsCallerSuppliedOwner() {
        String token = JWT.user("alice", "alice", "accounts.read");
        client.get()
                .uri("/api/accounts/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Owner-Id", "foreign-owner")
                .header("X-Account-Id", "foreign-account")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.login")
                .isEqualTo("alice");
        assertEquals("Bearer " + token, RECEIVED_TOKEN.get());
        assertFalse(RECEIVED_OWNER_HEADER.get());
    }

    @Test
    void rejectsAnonymousAndNeverExposesInternalRoutes() {
        client.get().uri("/api/accounts/me").exchange().expectStatus().isUnauthorized();
        client.post()
                .uri("/internal/accounts/operations")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + JWT.user("alice", "alice", "accounts.write"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void verifiesSignatureIssuerAudienceExpiryAndUserClientBeforeRouting() {
        try (JwtFixture attacker = new JwtFixture()) {
            List<String> invalid =
                    List.of(
                            JWT.token(
                                    "alice",
                                    "bank-front",
                                    List.of("bank-api"),
                                    "accounts.read",
                                    "https://other-issuer.example",
                                    Instant.now().plusSeconds(300),
                                    "alice"),
                            JWT.token(
                                    "alice",
                                    "bank-front",
                                    null,
                                    "accounts.read",
                                    JWT.issuer(),
                                    Instant.now().plusSeconds(300),
                                    "alice"),
                            JWT.token(
                                    "alice",
                                    "bank-front",
                                    List.of("wrong-api"),
                                    "accounts.read",
                                    JWT.issuer(),
                                    Instant.now().plusSeconds(300),
                                    "alice"),
                            JWT.token(
                                    "alice",
                                    "bank-front",
                                    List.of("bank-api"),
                                    "accounts.read",
                                    JWT.issuer(),
                                    Instant.now().minusSeconds(120),
                                    "alice"),
                            attacker.token(
                                    "alice",
                                    "bank-front",
                                    List.of("bank-api"),
                                    "accounts.read",
                                    JWT.issuer(),
                                    Instant.now().plusSeconds(300),
                                    "alice"));
            for (String token : invalid) {
                client.get()
                        .uri("/api/accounts/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .exchange()
                        .expectStatus()
                        .isUnauthorized();
            }
        }
        client.get()
                .uri("/api/accounts/me")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + JWT.service("cash-service", "bank-api", "accounts.read"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private static HttpServer accountsServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/accounts/me",
                    exchange -> {
                        RECEIVED_TOKEN.set(
                                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
                        RECEIVED_OWNER_HEADER.set(
                                exchange.getRequestHeaders().containsKey("X-Owner-Id")
                                        || exchange.getRequestHeaders()
                                                .containsKey("X-Account-Id"));
                        byte[] body = "{\"login\":\"alice\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start gateway fixture", exception);
        }
    }
}
