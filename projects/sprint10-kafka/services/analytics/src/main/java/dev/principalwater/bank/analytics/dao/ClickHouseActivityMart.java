package dev.principalwater.bank.analytics.dao;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.bank.analytics.model.ActivitySnapshot;
import dev.principalwater.bank.analytics.model.AnalyticsUnavailable;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class ClickHouseActivityMart implements AutoCloseable {
    // Один month из двух CQL partitions даёт не более 2000 исходных записей; 512KiB ограничивают
    // serialisation memory.
    private static final int MAX_BATCH_BYTES = 524288;
    // Один exporter использует одно соединение; 6s ограничивают всю async operation поверх
    // socket/server timeouts.
    private static final int CONNECT_SECONDS = 1;
    private static final int IO_SECONDS = 4;
    private static final int RESPONSE_SECONDS = 6;
    private final Client client;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor io =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    Thread.ofPlatform().name("analytics-mart-http-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());

    public ClickHouseActivityMart(
            @Value("${bank.analytics.clickhouse-url}") URI endpoint,
            @Value("${bank.analytics.clickhouse-user}") String user,
            @Value("${bank.analytics.clickhouse-password}") String password,
            ObjectMapper mapper) {
        this.mapper = mapper;
        if (!List.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid ClickHouse activity configuration");
        }
        client =
                new Client.Builder()
                        .addEndpoint(endpoint.toString())
                        .setUsername(user)
                        .setPassword(password)
                        .setMaxConnections(1)
                        .setConnectTimeout(CONNECT_SECONDS, ChronoUnit.SECONDS)
                        .setConnectionRequestTimeout(CONNECT_SECONDS, ChronoUnit.SECONDS)
                        .setSocketTimeout(IO_SECONDS, ChronoUnit.SECONDS)
                        .setExecutionTimeout(IO_SECONDS, ChronoUnit.SECONDS)
                        .setMaxRetries(0)
                        .useAsyncRequests(true)
                        .setSharedOperationExecutor(io)
                        .serverSetting("async_insert", "1")
                        .serverSetting("wait_for_async_insert", "1")
                        .serverSetting("async_insert_busy_timeout_ms", "200")
                        .build();
    }

    public void insert(List<ActivitySnapshot.Row> rows) {
        if (rows.isEmpty()) return;
        CompletableFuture<InsertResponse> operation = null;
        try {
            StringBuilder payload = new StringBuilder();
            for (ActivitySnapshot.Row row : rows)
                payload.append(
                                mapper.writeValueAsString(
                                        Map.of(
                                                "owner_id",
                                                row.ownerId(),
                                                "event_date",
                                                row.date().toString(),
                                                "source",
                                                row.source(),
                                                "event_type",
                                                row.type(),
                                                "section",
                                                row.section(),
                                                "events_count",
                                                row.eventsCount(),
                                                "revision",
                                                row.revision())))
                        .append('\n');
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BATCH_BYTES)
                throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.PROJECTION_LIMIT);
            // Client-v2 использует HTTP; это пакетный Cassandra-derived путь, а не новый TCP
            // protocol.
            operation =
                    client.insert(
                            "bank_analytics.activity_snapshots",
                            new ByteArrayInputStream(bytes),
                            ClickHouseFormat.JSONEachRow);
            try (InsertResponse response = operation.get(RESPONSE_SECONDS, TimeUnit.SECONDS)) {
                response.getMetrics();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.WAREHOUSE, failure);
        } catch (JsonProcessingException failure) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.INVALID_RESPONSE, failure);
        } catch (AnalyticsUnavailable failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AnalyticsUnavailable(AnalyticsUnavailable.Category.WAREHOUSE, failure);
        } finally {
            if (operation != null && !operation.isDone()) operation.cancel(true);
        }
    }

    @Override
    public void close() {
        client.close();
        io.shutdownNow();
    }
}
