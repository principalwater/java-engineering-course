package dev.principalwater.bank.analytics.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.util.UriComponentsBuilder;

@Repository
public class ClickHouseWarehouse implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(4);
    private static final long EVENT_VERSION = 1;
    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final Semaphore permits = new Semaphore(4);
    private final URI endpoint;
    private final String user;
    private final String password;
    private final ObjectMapper mapper;
    private final ObjectReader rows;

    public ClickHouseWarehouse(
            @Value("${bank.analytics.clickhouse-url}") URI endpoint,
            @Value("${bank.analytics.clickhouse-user}") String user,
            @Value("${bank.analytics.clickhouse-password}") String password,
            ObjectMapper mapper) {
        if (!List.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || user.isBlank()
                || password.isBlank()) {
            throw new IllegalArgumentException("Invalid ClickHouse configuration");
        }
        this.endpoint = endpoint;
        this.user = user;
        this.password = password;
        this.mapper = mapper;
        rows =
                mapper.readerFor(WarehouseRow.class)
                        .with(
                                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public void insert(BankEvent event) {
        try {
            String payload =
                    "INSERT INTO bank_analytics.events FORMAT JSONEachRow\n"
                            + mapper.writeValueAsString(
                                    Map.of(
                                            "event_id",
                                            event.eventId(),
                                            "owner_id",
                                            event.ownerId(),
                                            "event_type",
                                            event.type(),
                                            "amount_minor",
                                            event.amountMinor(),
                                            "occurred_at",
                                            EVENT_TIME.format(event.occurredAt()),
                                            "version",
                                            EVENT_VERSION))
                            + '\n';
            URI uri =
                    base().queryParam("async_insert", 1)
                            .queryParam("wait_for_async_insert", 1)
                            .queryParam("async_insert_busy_timeout_ms", 200)
                            .build()
                            .toUri();
            if (!new String(execute(uri, payload), StandardCharsets.UTF_8).isBlank()) {
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.INVALID_RESPONSE);
            }
        } catch (JsonProcessingException exception) {
            throw new AnalyticsUnavailable(
                    AnalyticsUnavailable.Category.INVALID_RESPONSE, exception);
        }
    }

    public AnalyticsView month(YearMonth month) {
        URI uri =
                base().queryParam("param_start", month.atDay(1))
                        .queryParam("param_end", month.plusMonths(1).atDay(1))
                        .build()
                        .toUri();
        String sql =
                """
                SELECT toDate(occurred_at) AS date, grouping(date) AS summary,
                    sumIf(amount_minor, amount_minor > 0) AS incomingMinor,
                    -sumIf(amount_minor, amount_minor < 0) AS outgoingMinor,
                    countIf(event_type NOT IN ('CABINET_VIEW', 'TRANSFER_IN')) AS operationsCount,
                    uniqExact(owner_id) AS accountsCount, countIf(event_type = 'CABINET_VIEW') AS viewCount,
                    count() AS eventCount, max(toUnixTimestamp64Milli(occurred_at)) AS updatedAtMillis
                FROM bank_analytics.events FINAL
                WHERE event_date >= {start:Date} AND event_date < {end:Date}
                GROUP BY GROUPING SETS ((date), ()) ORDER BY summary, date LIMIT 32
                SETTINGS max_execution_time=2, max_rows_to_read=100000, max_bytes_to_read=16777216,
                    read_overflow_mode='throw', max_result_rows=32, result_overflow_mode='throw',
                    max_result_bytes=65536, max_memory_usage=67108864, output_format_json_quote_64bit_integers=0
                FORMAT JSONEachRow
                """;
        try (MappingIterator<WarehouseRow> values = rows.readValues(execute(uri, sql))) {
            List<WarehouseRow> result = values.readAll();
            List<WarehouseRow> totals = result.stream().filter(row -> row.summary() == 1).toList();
            if (totals.size() != 1 || result.size() > 32) {
                throw new IOException("Invalid warehouse summary");
            }
            WarehouseRow total = totals.getFirst();
            List<AnalyticsView.Day> days =
                    result.stream()
                            .filter(
                                    row ->
                                            row.summary() == 0
                                                    && (row.incomingMinor() > 0
                                                            || row.outgoingMinor() > 0))
                            .map(
                                    row ->
                                            new AnalyticsView.Day(
                                                    row.date(),
                                                    safeAmount(row.incomingMinor()),
                                                    safeAmount(row.outgoingMinor())))
                            .toList();
            return new AnalyticsView(
                    true,
                    days,
                    total.operationsCount(),
                    total.accountsCount(),
                    total.viewCount(),
                    total.eventCount() == 0 ? null : Instant.ofEpochMilli(total.updatedAtMillis()));
        } catch (IOException | IllegalArgumentException exception) {
            throw new AnalyticsUnavailable(
                    AnalyticsUnavailable.Category.INVALID_RESPONSE, exception);
        }
    }

    private long safeAmount(long value) {
        if (value < 0 || value > BankLimits.MAX_BALANCE_MINOR) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_LIMIT);
        }
        return value;
    }

    private UriComponentsBuilder base() {
        return UriComponentsBuilder.fromUri(endpoint).queryParam("wait_end_of_query", 1);
    }

    private byte[] execute(URI uri, String payload) {
        if (!permits.tryAcquire()) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_LIMIT);
        }
        LimitedBody body = new LimitedBody();
        CompletableFuture<HttpResponse<byte[]>> request = null;
        try {
            HttpRequest outgoing =
                    HttpRequest.newBuilder(uri)
                            .timeout(OPERATION_TIMEOUT)
                            .header("X-ClickHouse-User", user)
                            .header("X-ClickHouse-Key", password)
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            payload, StandardCharsets.UTF_8))
                            .build();
            request = http.sendAsync(outgoing, ignored -> body);
            HttpResponse<byte[]> response =
                    request.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200
                    || response.headers().allValues("X-ClickHouse-Exception-Code").stream()
                            .anyMatch(code -> !code.equals("0"))) {
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.WAREHOUSE);
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.WAREHOUSE, exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.WAREHOUSE, exception);
        } finally {
            body.cancel();
            if (request != null && !request.isDone()) {
                request.cancel(true);
            }
            permits.release();
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    private record WarehouseRow(
            LocalDate date,
            int summary,
            long incomingMinor,
            long outgoingMinor,
            long operationsCount,
            long accountsCount,
            long viewCount,
            long eventCount,
            long updatedAtMillis) {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
                    cancel();
                    result.completeExceptionally(
                            new IOException("Warehouse response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }

        void cancel() {
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }
}
