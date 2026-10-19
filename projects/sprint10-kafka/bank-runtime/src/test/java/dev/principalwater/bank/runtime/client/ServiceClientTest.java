package dev.principalwater.bank.runtime.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import dev.principalwater.bank.testing.BankFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class ServiceClientTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger accountRequests = new AtomicInteger();
    private final AtomicInteger h2cUpgrades = new AtomicInteger();
    private final InternalOperation command =
            BankFixtures.deposit(BankFixtures.actor("alice"), 100);
    private HttpServer server;
    private ExecutorService httpWorkers;
    private ServiceClient client;
    private volatile int status;
    private volatile String error;
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;

    @BeforeEach
    void start() throws IOException {
        status = 200;
        httpWorkers = Executors.newFixedThreadPool(16);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext(
                "/token",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    if (!"POST".equals(exchange.getRequestMethod())
                            || !exchange.getRequestHeaders().containsKey("Authorization")) {
                        respond(exchange, 400, "{}");
                        return;
                    }
                    tokenRequests.incrementAndGet();
                    respond(
                            exchange,
                            200,
                            "{\"access_token\":\"fixture-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}");
                });
        server.createContext("/internal/accounts/operations", this::account);
        server.start();
        int port = server.getAddress().getPort();
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("service")
                        .clientId("fixture-cash-service")
                        .clientSecret("fixture-secret")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri("http://127.0.0.1:" + port + "/token")
                        .scope("accounts.cash")
                        .build();
        InMemoryClientRegistrationRepository registrations =
                new InMemoryClientRegistrationRepository(registration);
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances("accounts"))
                .thenReturn(
                        List.of(
                                new DefaultServiceInstance(
                                        "fixture-accounts", "accounts", "127.0.0.1", port, false)));
        client =
                new ServiceClientConfiguration()
                        .serviceClient(
                                discovery,
                                registrations,
                                new InMemoryOAuth2AuthorizedClientService(registrations),
                                mapper,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        if (release != null) {
            release.countDown();
        }
        SecurityContextHolder.clearContext();
        server.stop(0);
        httpWorkers.shutdownNow();
    }

    @Test
    void clientCredentialsAreReusedAcrossHumanSecurityContexts() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "unused"));
        OperationResult first =
                client.post(
                        "accounts",
                        "/internal/accounts/operations",
                        command,
                        OperationResult.class);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("bob", "unused"));
        assertThat(
                        client.post(
                                "accounts",
                                "/internal/accounts/operations",
                                command,
                                OperationResult.class))
                .isEqualTo(first);
        assertThat(first.operationId()).isEqualTo(command.operationId());
        assertThat(tokenRequests).hasValue(1);
        assertThat(accountRequests).hasValue(2);
        assertThat(h2cUpgrades).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_REQUEST", "409,OPERATION_CONFLICT", "422,INSUFFICIENT_FUNDS"})
    void knownBusinessErrorsKeepTheirCodesWithoutAutomaticWriteRetry(
            int responseStatus, String code) {
        status = responseStatus;
        error = code;
        assertThatThrownBy(
                        () ->
                                client.post(
                                        "accounts",
                                        "/internal/accounts/operations",
                                        command,
                                        OperationResult.class))
                .isInstanceOfSatisfying(
                        BankException.class,
                        exception ->
                                assertThat(exception.getError())
                                        .isEqualTo(BankError.valueOf(code)));
        assertThat(accountRequests).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"302", "401", "403", "408", "429", "503"})
    void unavailableAuthorizationOrTargetKeepsOutcomeUnknown(int responseStatus) {
        status = responseStatus;
        error = "UNAVAILABLE";
        assertThatThrownBy(
                        () ->
                                client.post(
                                        "accounts",
                                        "/internal/accounts/operations",
                                        command,
                                        OperationResult.class))
                .isInstanceOf(RemoteUnavailable.class);
        assertThat(accountRequests).hasValue(1);
    }

    @Test
    void redirectCannotAcknowledgeVoidDelivery() {
        status = 302;
        error = "UNAVAILABLE";
        assertThatThrownBy(
                        () ->
                                client.post(
                                        "accounts",
                                        "/internal/accounts/operations",
                                        command,
                                        Void.class))
                .isInstanceOf(RemoteUnavailable.class);
        assertThat(accountRequests).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"400,UNKNOWN", "409,UNKNOWN", "400,OPERATION_CONFLICT", "418,INVALID_REQUEST"})
    void opaqueOrMismatchedErrorsCannotInventFinalBusinessOutcome(int responseStatus, String code) {
        status = responseStatus;
        error = code;
        assertThatThrownBy(
                        () ->
                                client.post(
                                        "accounts",
                                        "/internal/accounts/operations",
                                        command,
                                        OperationResult.class))
                .isInstanceOf(RemoteUnavailable.class);
        assertThat(accountRequests).hasValue(1);
    }

    @Test
    void ninthConcurrentRequestIsRejectedBeforeAnotherNetworkCall() throws Exception {
        client.post("accounts", "/internal/accounts/operations", command, OperationResult.class);
        entered = new CountDownLatch(8);
        release = new CountDownLatch(1);
        ExecutorService requests = Executors.newFixedThreadPool(8);
        List<Future<OperationResult>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                pending.add(
                        requests.submit(
                                () ->
                                        client.post(
                                                "accounts",
                                                "/internal/accounts/operations",
                                                command,
                                                OperationResult.class)));
            }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(
                            () ->
                                    client.post(
                                            "accounts",
                                            "/internal/accounts/operations",
                                            command,
                                            OperationResult.class))
                    .isInstanceOf(RemoteUnavailable.class);
            assertThat(accountRequests).hasValue(9);
            release.countDown();
            for (Future<OperationResult> request : pending) {
                assertThat(request.get(3, TimeUnit.SECONDS).status())
                        .isEqualTo(OperationStatus.SUCCEEDED);
            }
        } finally {
            release.countDown();
            requests.shutdownNow();
        }
    }

    private void account(HttpExchange exchange) throws IOException {
        accountRequests.incrementAndGet();
        if ("h2c".equals(exchange.getRequestHeaders().getFirst("Upgrade"))) {
            h2cUpgrades.incrementAndGet();
        }
        if (!"Bearer fixture-service-token"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{}");
            return;
        }
        InternalOperation operation =
                mapper.readValue(exchange.getRequestBody(), InternalOperation.class);
        if (entered != null) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    respond(exchange, 503, "{}");
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        String body =
                status == 200
                        ? mapper.writeValueAsString(
                                new OperationResult(
                                        operation.operationId(),
                                        OperationStatus.SUCCEEDED,
                                        null,
                                        UUID.fromString("3f1f6b67-5ad9-4c39-bfb8-e0a201978341"),
                                        100L,
                                        Instant.parse("2026-10-17T12:00:00Z")))
                        : "{\"code\":\"" + error + "\"}";
        respond(exchange, status, body);
    }

    private void respond(HttpExchange exchange, int responseStatus, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
