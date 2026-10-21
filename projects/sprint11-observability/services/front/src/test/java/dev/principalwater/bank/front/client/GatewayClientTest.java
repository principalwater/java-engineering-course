package dev.principalwater.bank.front.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class GatewayClientTest {
    @Test
    void unavailableGatewayKeepsAnExplicitOutcomeAndLogsNoTokenPathOrPayload(CapturedOutput output)
            throws Exception {
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        GatewayClient client =
                new GatewayClient(
                        discovery,
                        RestClient.builder(),
                        Duration.ofMillis(100),
                        Duration.ofMillis(200));
        when(discovery.getInstances("gateway")).thenReturn(List.of());
        assertUnavailable(client);
        assertThat(output.getAll()).contains("Gateway unavailable: discovery");
        // Соединение возможно, но upstream не отвечает: проверяем настоящий read deadline.
        try (ServerSocket unresponsive = new ServerSocket(0)) {
            when(discovery.getInstances("gateway"))
                    .thenReturn(
                            List.of(
                                    new DefaultServiceInstance(
                                            "gateway",
                                            "gateway",
                                            "127.0.0.1",
                                            unresponsive.getLocalPort(),
                                            false)));
            assertUnavailable(client);
        }
        assertThat(output.getAll())
                .contains("Gateway unavailable: transport")
                .doesNotContain("private-search", "private-token", "private-body");
    }

    private void assertUnavailable(GatewayClient client) {
        assertThatThrownBy(
                        () ->
                                client.exchange(
                                        HttpMethod.POST,
                                        "/api/accounts/me?search=private-search",
                                        Map.of("value", "private-body"),
                                        "private-token"))
                .isInstanceOfSatisfying(
                        BankException.class,
                        exception ->
                                assertThat(exception.getError()).isEqualTo(BankError.UNAVAILABLE));
    }
}
