package dev.principalwater.bank.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Минимальный read-only scrape для non-web worker: один путь, один метод, без бизнес-API.
// Источник метрик — тот же PrometheusMeterRegistry, что и у Actuator в HTTP-сервисах.
public final class PrometheusScrapeServer {
    // Тот же путь, что у Actuator в HTTP-сервисах, чтобы Prometheus опрашивал все цели одинаково.
    public static final String PATH = "/actuator/prometheus";
    // Формат 0.0.4 принимает любая версия Prometheus независимо от заголовка Accept.
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    // Очередь принятых, но ещё не обслуженных соединений ядра; остальное отклоняется.
    private static final int BACKLOG = 8;
    // Два параллельных scrape (например, две реплики Prometheus) и короткая очередь за ними;
    // при переполнении executor отклоняет задачу, а HttpServer закрывает соединение.
    private static final int WORKERS = 2;
    private static final int QUEUED_SCRAPES = 4;
    // Время на доработку уже принятого ответа при остановке.
    private static final int STOP_DELAY_SECONDS = 1;
    private static final int STOP_TERMINATION_SECONDS = 2;
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private final PrometheusMeterRegistry registry;
    private final String host;
    private final int requestedPort;
    private HttpServer server;
    private ThreadPoolExecutor workers;

    public PrometheusScrapeServer(PrometheusMeterRegistry registry, String host, int port) {
        this.registry = registry;
        this.host = host;
        this.requestedPort = port;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        // Сначала bind: при занятом порту потоки и executor не создаются.
        HttpServer created = HttpServer.create(new InetSocketAddress(host, requestedPort), BACKLOG);
        workers =
                new ThreadPoolExecutor(
                        WORKERS,
                        WORKERS,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(QUEUED_SCRAPES),
                        Thread.ofPlatform().name("bank-metrics-", 0).daemon().factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        created.createContext("/", this::handle);
        created.setExecutor(workers);
        created.start();
        server = created;
    }

    // Фактический порт нужен, когда запрошен 0 (тесты и динамические окружения).
    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("Metrics server is not running");
        }
        return server.getAddress().getPort();
    }

    // Повторная остановка безопасна: bean закрывается и контекстом, и вызывающим кодом.
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(STOP_DELAY_SECONDS);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(STOP_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException failure) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        server = null;
        workers = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Тело запроса не читается: close() сам ограниченно вычитывает остаток или рвёт соединение.
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(NOT_FOUND, -1);
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, -1);
            } else {
                byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
                exchange.sendResponseHeaders(OK, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
        }
    }
}
