package dev.principalwater.bank.runtime.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.runtime.error.BankError;
import dev.principalwater.bank.runtime.error.BankException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

public class ServiceClient {
    private static final int MAX_IN_FLIGHT = 8;
    private static final int MAX_ERROR_BYTES = 16_384;
    private static final String APPLICATION_PRINCIPAL = "bank-service-application";
    private final DiscoveryClient discovery;
    private final OAuth2AuthorizedClientManager authorizedClients;
    private final RestClient http;
    private final ObjectMapper mapper;
    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);
    private final Map<String, CircuitBreaker> breakers;

    public ServiceClient(
            DiscoveryClient discovery,
            OAuth2AuthorizedClientManager authorizedClients,
            RestClient http,
            ObjectMapper mapper) {
        this.discovery = discovery;
        this.authorizedClients = authorizedClients;
        this.http = http;
        this.mapper = mapper;
        CircuitBreakerConfig config =
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .ignoreExceptions(BankException.class)
                        .build();
        breakers =
                List.of("accounts").stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        name -> name, name -> CircuitBreaker.of(name, config)));
    }

    public <T> T post(String service, String path, Object body, Class<T> responseType) {
        return execute(
                service,
                () ->
                        send(
                                service,
                                path,
                                body,
                                ParameterizedTypeReference.forType(responseType),
                                HttpMethod.POST));
    }

    public <T> T get(String service, String path, Class<T> responseType) {
        return get(service, path, ParameterizedTypeReference.forType(responseType));
    }

    public <T> T get(String service, String path, ParameterizedTypeReference<T> responseType) {
        return execute(service, () -> send(service, path, null, responseType, HttpMethod.GET));
    }

    private <T> T execute(String service, Supplier<T> request) {
        CircuitBreaker breaker = breakers.get(service);
        if (breaker == null) {
            throw new IllegalArgumentException("Unknown target service");
        }
        if (!permits.tryAcquire()) {
            throw new RemoteUnavailable();
        }
        try {
            return breaker.executeSupplier(request);
        } catch (BankException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Ответ мог потеряться после commit: write автоматически не повторяется.
            throw new RemoteUnavailable();
        } finally {
            permits.release();
        }
    }

    private <T> T send(
            String service,
            String path,
            Object body,
            ParameterizedTypeReference<T> responseType,
            HttpMethod method) {
        List<ServiceInstance> instances = discovery.getInstances(service);
        if (instances.size() != 1) {
            throw new RemoteUnavailable();
        }
        URI base = instances.getFirst().getUri();
        URI route = URI.create(path);
        if (!path.startsWith("/")
                || path.startsWith("//")
                || route.isAbsolute()
                || route.getRawAuthority() != null
                || route.getFragment() != null
                || base.getUserInfo() != null
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))) {
            throw new IllegalArgumentException("Invalid service route");
        }
        OAuth2AuthorizedClient client =
                authorizedClients.authorize(
                        OAuth2AuthorizeRequest.withClientRegistrationId("service")
                                .principal(APPLICATION_PRINCIPAL)
                                .build());
        if (client == null) {
            throw new RemoteUnavailable();
        }
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path(route.getRawPath())
                        .query(route.getRawQuery())
                        .build(true)
                        .toUri();
        RestClient.RequestBodySpec request =
                http.method(method)
                        .uri(uri)
                        .headers(
                                headers ->
                                        headers.setBearerAuth(
                                                client.getAccessToken().getTokenValue()));
        if (body != null) {
            request.body(body);
        }
        T result =
                request.retrieve()
                        .onStatus(
                                status -> !status.is2xxSuccessful(),
                                (outgoing, response) -> reject(response))
                        .body(responseType);
        if (result == null && responseType.getType() != Void.class) {
            throw new RemoteUnavailable();
        }
        return result;
    }

    private void reject(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status >= 500 || status == 401 || status == 403 || status == 408 || status == 429) {
            throw new RemoteUnavailable();
        }
        byte[] bytes = response.getBody().readNBytes(MAX_ERROR_BYTES + 1);
        if (bytes.length <= MAX_ERROR_BYTES) {
            try {
                JsonNode json = mapper.readTree(bytes);
                BankError error = BankError.valueOf(json == null ? "" : json.path("code").asText());
                if (error.getStatus() == status) {
                    throw new BankException(error);
                }
            } catch (IllegalArgumentException | IOException exception) {
                // В журнал и клиентский ответ не попадают внешнее тело и credentials.
            }
        }
        throw new RemoteUnavailable();
    }
}
