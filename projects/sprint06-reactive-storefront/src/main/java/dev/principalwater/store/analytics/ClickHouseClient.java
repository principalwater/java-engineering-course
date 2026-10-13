package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

public class ClickHouseClient implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_CONNECTIONS = 4;
    private static final int MAX_PENDING_REQUESTS = 16;
    private static final String INSERT = "INSERT INTO storefront.order_lines FORMAT JSONEachRow";
    private final AnalyticsProperties properties;
    private final ObjectMapper json;
    private final ObjectReader aggregateReader;
    private final ConnectionProvider connections;
    private final WebClient http;
    private final String dashboardQuery;

    public ClickHouseClient(AnalyticsProperties properties, ObjectMapper json, WebClient.Builder builder) {
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
        // Ресурс читается один раз при создании бина, до обработки HTTP-запросов.
        try (var input = new ClassPathResource("analytics/dashboard.sql").getInputStream()) {
            dashboardQuery = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Analytics query resource is missing", exception);
        }
        connections = ConnectionProvider.builder("storefront-analytics").maxConnections(MAX_CONNECTIONS)
                .pendingAcquireMaxCount(MAX_PENDING_REQUESTS).pendingAcquireTimeout(CONNECT_TIMEOUT).build();
        var transport = HttpClient.create(connections).followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(CONNECT_TIMEOUT.toMillis()))
                .responseTimeout(OPERATION_TIMEOUT);
        http = builder.clone().clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES)).build();
    }

    public Mono<Void> insert(List<AnalyticsFact> facts) {
        if (facts.isEmpty()) {
            return Mono.empty();
        }
        // Ограниченный пакет сериализуется вне потоков Netty и R2DBC; транспорт остаётся неблокирующим.
        return Mono.fromCallable(() -> encode(facts)).subscribeOn(Schedulers.parallel()).flatMap(payload -> {
            URI uri = endpoint().queryParam("async_insert", 1).queryParam("wait_for_async_insert", 1)
                    .queryParam("wait_end_of_query", 1).build().toUri();
            return execute(uri, payload, "storefront_writer", properties.getWriterPassword());
        }).flatMap(response -> {
            // Поздняя ошибка может сопровождать HTTP 200; успешный INSERT имеет пустое подтверждение.
            if (!new String(response, StandardCharsets.UTF_8).isBlank()) {
                return Mono.error(unavailable(new IOException("Unexpected ClickHouse insertion acknowledgement")));
            }
            return Mono.<Void>empty();
        });
    }

    public Mono<List<AnalyticsRow>> dashboard(UUID sourceId, LocalDate start, LocalDate end) {
        return Mono.defer(() -> {
            URI uri = endpoint().queryParam("param_source", sourceId).queryParam("param_start", start)
                    .queryParam("param_end", end).queryParam("param_limit", AnalyticsPeriod.MAXIMUM_DAYS)
                    .queryParam("wait_end_of_query", 1).build().toUri();
            return execute(uri, dashboardQuery, "storefront_reader", properties.getReaderPassword()).map(response -> {
                try (MappingIterator<AnalyticsRow> rows = aggregateReader.readValues(response)) {
                    return rows.readAll();
                } catch (IOException | IllegalArgumentException exception) {
                    throw unavailable(exception);
                }
            });
        });
    }

    private String encode(List<AnalyticsFact> facts) throws IOException {
        var payload = new StringBuilder(INSERT).append('\n');
        for (var fact : facts) {
            payload.append(json.writeValueAsString(fact)).append('\n');
        }
        return payload.toString();
    }

    private UriComponentsBuilder endpoint() {
        return UriComponentsBuilder.fromUri(properties.getUrl());
    }

    private Mono<byte[]> execute(URI uri, String payload, String user, String password) {
        return http.post().uri(uri).header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password)
                .contentType(MediaType.TEXT_PLAIN).bodyValue(payload)
                .exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        .flatMap(body -> {
                            boolean rejected = response.statusCode() != HttpStatus.OK || response.headers()
                                    .header("X-ClickHouse-Exception-Code").stream().anyMatch(code -> !code.equals("0"));
                            return rejected ? Mono.error(unavailable(new IOException("ClickHouse rejected the request: HTTP "
                                    + response.statusCode().value()))) : Mono.just(body);
                        }))
                // Бюджет покрывает получение соединения, заголовки и всё тело; отмена прекращает клиентский I/O.
                .timeout(OPERATION_TIMEOUT)
                .onErrorMap(error -> error instanceof WebClientException || error instanceof TimeoutException
                        || error instanceof IOException || error instanceof io.netty.handler.timeout.TimeoutException
                        || error instanceof DataBufferLimitException, this::unavailable);
    }

    private StoreException unavailable(Throwable cause) {
        return new StoreException(StoreError.ANALYTICS_UNAVAILABLE, cause);
    }

    @Override
    public void close() {
        connections.dispose();
    }
}
