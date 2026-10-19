package dev.principalwater.bank.cash.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.testing.BankFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

class AccountsConsumerContractTest {
    @RegisterExtension
    static final StubRunnerExtension STUBS =
            new StubRunnerExtension()
                    .downloadStubs("dev.principalwater.bank:accounts:1.0.0:stubs")
                    .stubsMode(StubRunnerProperties.StubsMode.CLASSPATH);

    @ParameterizedTest
    @MethodSource("commands")
    void productionServiceClientConsumesVerifiedCashAndTransferProtocols(
            InternalOperation command, long expectedBalance) throws Exception {
        try (TokenEndpoint token = new TokenEndpoint()) {
            URI accounts = STUBS.findStubUrl("dev.principalwater.bank", "accounts").toURI();
            DiscoveryClient discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("accounts"))
                    .thenReturn(
                            List.of(
                                    new DefaultServiceInstance(
                                            "verified-contract",
                                            "accounts",
                                            accounts.getHost(),
                                            accounts.getPort(),
                                            false)));
            ClientRegistration registration =
                    ClientRegistration.withRegistrationId("service")
                            .clientId(
                                    command.type() == OperationType.TRANSFER
                                            ? "transfer-service"
                                            : "cash-service")
                            .clientSecret("fixture-secret")
                            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                            .tokenUri(token.uri())
                            .scope(
                                    command.type() == OperationType.TRANSFER
                                            ? "accounts.transfer"
                                            : "accounts.cash")
                            .build();
            InMemoryClientRegistrationRepository registrations =
                    new InMemoryClientRegistrationRepository(registration);
            AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                    new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                            registrations,
                            new InMemoryOAuth2AuthorizedClientService(registrations));
            manager.setAuthorizedClientProvider(
                    OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
            JdkClientHttpRequestFactory requests =
                    new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder()
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .connectTimeout(Duration.ofSeconds(1))
                                    .build());
            requests.setReadTimeout(Duration.ofSeconds(3));
            ServiceClient client =
                    new ServiceClient(
                            discovery,
                            manager,
                            RestClient.builder().requestFactory(requests).build(),
                            new ObjectMapper().findAndRegisterModules());

            OperationResult result =
                    client.post(
                            "accounts",
                            "/internal/accounts/operations",
                            command,
                            OperationResult.class);
            assertThat(result.operationId()).isEqualTo(command.operationId());
            assertThat(result.status()).isEqualTo(OperationStatus.SUCCEEDED);
            assertThat(result.accountId()).isNotNull();
            assertThat(result.balanceMinor()).isEqualTo(expectedBalance);
            assertThat(result.occurredAt()).isNotNull();
            assertThat(token.requests).hasValue(1);
        }
    }

    static Stream<Arguments> commands() {
        Actor alice = BankFixtures.actor("alice");
        Actor carol = BankFixtures.actor("carol");
        return Stream.of(
                Arguments.of(
                        new InternalOperation(
                                alice,
                                UUID.fromString("1582c2bb-3d60-4aae-bb70-d30872652374"),
                                OperationType.DEPOSIT,
                                1000L,
                                null),
                        1000L),
                Arguments.of(
                        new InternalOperation(
                                carol,
                                UUID.fromString("6c096269-ac7d-4aa7-8df6-8dbd094ab483"),
                                OperationType.TRANSFER,
                                500L,
                                UUID.fromString("c465e4cf-4080-480f-9fdd-5eb550be9ff9")),
                        4500L));
    }

    private static final class TokenEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final AtomicInteger requests = new AtomicInteger();

        TokenEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(worker);
            server.createContext(
                    "/token",
                    exchange -> {
                        String form =
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8);
                        boolean valid =
                                form.contains("grant_type=client_credentials")
                                        && exchange.getRequestHeaders()
                                                .containsKey("Authorization");
                        byte[] response =
                                (valid
                                                ? "{\"access_token\":\"contract-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                                                : "{\"error\":\"invalid_request\"}")
                                        .getBytes(StandardCharsets.UTF_8);
                        requests.incrementAndGet();
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(valid ? 200 : 400, response.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(response);
                        }
                    });
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        }

        @Override
        public void close() {
            server.stop(0);
            worker.shutdownNow();
        }
    }
}
