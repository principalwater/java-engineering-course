package dev.principalwater.bank.front.client;

import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import dev.principalwater.bank.runtime.security.HttpRequests;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GatewayClient {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayClient.class);
    private static final int MAX_REPLY_BYTES = 131_072;
    private static final int MAX_IN_FLIGHT = 16;
    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);
    private final DiscoveryClient discovery;
    private final RestClient http;

    public GatewayClient(
            DiscoveryClient discovery,
            RestClient.Builder restClients,
            @Value("${bank.client.connect-timeout:" + HttpRequests.DEFAULT_CONNECT_TIMEOUT + "}")
                    Duration connect,
            @Value("${bank.front.gateway-timeout:8s}") Duration read) {
        this.discovery = discovery;
        JdkClientHttpRequestFactory requests = HttpRequests.create(connect, read);
        // Builder Boot несёт наблюдение: вызов Gateway становится span-потомком запроса Front и
        // передаёт traceparent; тайм-ауты задаёт собственная фабрика.
        http = restClients.requestFactory(requests).build();
    }

    public ResponseEntity<byte[]> exchange(
            HttpMethod method, String path, Object body, String userAccessToken) {
        if (!permits.tryAcquire()) {
            throw unavailable("capacity");
        }
        try {
            List<ServiceInstance> gateways = discovery.getInstances("gateway");
            if (gateways.size() != 1) {
                throw unavailable("discovery");
            }
            URI route = URI.create(path);
            if (!route.getRawPath().startsWith("/api/")
                    || route.isAbsolute()
                    || route.getRawAuthority() != null
                    || route.getFragment() != null) {
                throw new IllegalArgumentException("Invalid gateway route");
            }
            URI target =
                    UriComponentsBuilder.fromUri(gateways.getFirst().getUri())
                            .path(route.getRawPath())
                            .query(route.getRawQuery())
                            .build(true)
                            .toUri();
            RestClient.RequestBodySpec request =
                    http.method(method)
                            .uri(target)
                            .headers(headers -> headers.setBearerAuth(userAccessToken));
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange(
                    (outgoing, incoming) -> {
                        byte[] bytes = incoming.getBody().readNBytes(MAX_REPLY_BYTES + 1);
                        if (bytes.length > MAX_REPLY_BYTES) {
                            throw new IOException("Gateway reply exceeds limit");
                        }
                        return ResponseEntity.status(incoming.getStatusCode())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(bytes);
                    });
        } catch (ResourceAccessException exception) {
            // Неизвестный исход POST не превращается в повтор с новым идентификатором.
            throw unavailable("transport");
        } finally {
            permits.release();
        }
    }

    private BankException unavailable(String category) {
        // Путь, токен и тело ответа не нужны для сигнала доступности и не попадают в журнал.
        LOG.warn("Gateway unavailable: {}", category);
        return new BankException(BankError.UNAVAILABLE);
    }
}
