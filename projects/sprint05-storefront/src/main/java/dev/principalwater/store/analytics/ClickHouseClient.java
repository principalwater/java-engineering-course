package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;

public class ClickHouseClient implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final String INSERT = "INSERT INTO storefront.order_lines FORMAT JSONEachRow";
    private final AnalyticsProperties properties;
    private final ObjectMapper json;
    private final ObjectReader aggregateReader;
    private final HttpClient http;
    private final String dashboardQuery;

    public ClickHouseClient(AnalyticsProperties properties, ObjectMapper json) {
        URI url = properties.getUrl();
        if (url == null || !List.of("http", "https").contains(url.getScheme()) || url.getHost() == null
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("ClickHouse requires an HTTP(S) endpoint without credentials, query or fragment");
        }
        this.properties = properties;
        this.json = json;
        aggregateReader = json.readerFor(AnalyticsRow.class)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        try (var input = new ClassPathResource("analytics/dashboard.sql").getInputStream()) {
            dashboardQuery = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Analytics query resource is missing", exception);
        }
        http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void insert(List<AnalyticsFact> facts) {
        if (facts.isEmpty()) {
            return;
        }
        var payload = new StringBuilder(INSERT).append('\n');
        try {
            for (var fact : facts) {
                payload.append(json.writeValueAsString(fact)).append('\n');
            }
        } catch (IOException exception) {
            throw unavailable(exception);
        }
        URI uri = endpoint().queryParam("async_insert", 1).queryParam("wait_for_async_insert", 1)
                .queryParam("wait_end_of_query", 1).build().toUri();
        byte[] response = execute(uri, payload.toString(), "storefront_writer", properties.getWriterPassword());
        // HTTP 200 не гарантирует успех при поздней ошибке; INSERT должен вернуть пустое подтверждение.
        if (!new String(response, StandardCharsets.UTF_8).isBlank()) {
            throw unavailable(new IOException("Unexpected ClickHouse insertion acknowledgement"));
        }
    }

    public List<AnalyticsRow> dashboard(UUID sourceId, LocalDate start) {
        URI uri = endpoint().queryParam("param_source", sourceId).queryParam("param_start", start)
                .queryParam("wait_end_of_query", 1).build().toUri();
        byte[] response = execute(uri, dashboardQuery, "storefront_reader", properties.getReaderPassword());
        try (MappingIterator<AnalyticsRow> rows = aggregateReader.readValues(response)) {
            return rows.readAll();
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private UriComponentsBuilder endpoint() {
        return UriComponentsBuilder.fromUri(properties.getUrl());
    }

    private byte[] execute(URI uri, String payload, String user, String password) {
        var request = HttpRequest.newBuilder(uri).timeout(OPERATION_TIMEOUT)
                .header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        var future = http.sendAsync(request, response -> new LimitedBody());
        try {
            // get ограничивает всю операцию, включая тело; один connect/request timeout этого не доказывает.
            var response = future.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != HttpStatus.OK.value() || response.headers()
                    .firstValue("X-ClickHouse-Exception-Code").filter(code -> !code.equals("0")).isPresent()) {
                throw unavailable(new IOException("ClickHouse rejected the request: HTTP " + response.statusCode()));
            }
            return response.body();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (ExecutionException | TimeoutException exception) {
            future.cancel(true);
            throw unavailable(exception);
        }
    }

    private StoreException unavailable(Throwable cause) {
        return new StoreException(StoreError.ANALYTICS_UNAVAILABLE, cause);
    }

    @Override
    public void close() {
        http.close();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;

        @Override
        public CompletionStage<byte[]> getBody() { return body.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            body.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - received) {
                    subscription.cancel();
                    body.onError(new IOException("ClickHouse response exceeds the size limit"));
                    return;
                }
                received += buffer.remaining();
            }
            body.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) { body.onError(error); }

        @Override
        public void onComplete() { body.onComplete(); }
    }
}
