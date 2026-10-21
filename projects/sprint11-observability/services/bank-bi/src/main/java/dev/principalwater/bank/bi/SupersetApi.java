package dev.principalwater.bank.bi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class SupersetApi implements AutoCloseable {
    // Шесть графиков читают только месячные агрегаты; превышение — ошибка контракта, не повод снять
    // лимит.
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String API = "/api/v1/";
    private static final String JSON_TYPE = "application/json";
    private static final String ROLE = "BankAnalyst";
    private static final String RESULT = "result";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private String token;
    private String csrf;

    SupersetApi(URI endpoint, Path authority) throws GeneralSecurityException, IOException {
        if (!"https".equals(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !Set.of("", "/").contains(endpoint.getPath())) {
            throw new IllegalArgumentException("Invalid Superset endpoint");
        }
        this.endpoint = endpoint;
        http =
                HttpClient.newBuilder()
                        .sslContext(trust(authority))
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                        .build();
    }

    private static SSLContext trust(Path authority) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream input = Files.newInputStream(authority)) {
            store.setCertificateEntry(
                    "bank-local-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory trust =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    void login(String password) throws IOException, InterruptedException {
        token =
                json(
                                "POST",
                                "security/login",
                                Map.of(
                                        "username",
                                        "bank-bootstrap",
                                        "password",
                                        password,
                                        "provider",
                                        "db",
                                        "refresh",
                                        false))
                        .path("access_token")
                        .asText();
        if (token.isBlank()) throw new IOException("Superset did not return an access token");
        csrf = json("GET", "security/csrf_token/", null).path(RESULT).asText();
        if (csrf.isBlank()) throw new IOException("Superset did not return a CSRF token");
    }

    void importAssets(byte[] archive, String password) throws IOException, InterruptedException {
        String boundary = "bank-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(
                body,
                boundary,
                "passwords",
                mapper.writeValueAsString(Map.of(SupersetAssets.DATABASE_FILE, password)));
        body.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"bank.zip\"\r\nContent-Type: application/zip\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(archive);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        request(
                "POST",
                // Dashboard import не обновляет связанную БД/dataset: assets управляет всем
                // набором.
                "assets/import/",
                "multipart/form-data; boundary=" + boundary,
                body.toByteArray());
    }

    void grantDatasets() throws IOException, InterruptedException {
        List<JsonNode> roles = list("security/roles/");
        int roleId =
                roles.stream()
                        .filter(role -> ROLE.equals(role.path("name").asText()))
                        .mapToInt(role -> role.path("id").asInt())
                        .findFirst()
                        .orElse(0);
        if (roleId == 0)
            roleId = json("POST", "security/roles/", Map.of("name", ROLE)).path("id").asInt();
        if (roleId <= 0) throw new IOException("Superset analyst role is missing");
        Set<String> datasetPermissions = new HashSet<>();
        for (JsonNode dataset : list("dataset/")) {
            if (Set.of("financial_daily", "engagement_daily", "notification_daily")
                            .contains(dataset.path("table_name").asText())
                    && "bank_analytics".equals(dataset.path("schema").asText())) {
                JsonNode details =
                        json("GET", "dataset/" + dataset.path("id").asInt(), null).path(RESULT);
                String table = details.path("table_name").asText();
                if (!SupersetAssets.identity(table).equals(details.path("uuid").asText())
                        || !SupersetAssets.DATABASE_NAME.equals(
                                details.path("database").path("database_name").asText())) {
                    throw new IOException(
                            "Superset dataset identity does not match managed assets");
                }
                // API 6.1 не экспортирует perm; используем публичную форму datasource permission
                // vendor-модели.
                String permission =
                        "["
                                + SupersetAssets.DATABASE_NAME
                                + "].["
                                + table
                                + "](id:"
                                + details.path("id").asInt()
                                + ")";
                datasetPermissions.add(permission);
            }
        }
        if (datasetPermissions.size() != 3)
            throw new IOException("Expected exactly three BI datasets");
        List<Integer> grants = new ArrayList<>();
        for (JsonNode permission : list("security/permissions-resources/")) {
            if ("datasource_access".equals(permission.path("permission").path("name").asText())
                    && datasetPermissions.contains(
                            permission.path("view_menu").path("name").asText())) {
                grants.add(permission.path("id").asInt());
            }
        }
        if (grants.size() != 3) throw new IOException("Expected exactly three dataset permissions");
        // Vendor API заменяет набор целиком: случайно добавленные широкие права не сохраняются.
        json(
                "POST",
                "security/roles/" + roleId + "/permissions",
                Map.of("permission_view_menu_ids", grants));
    }

    void verifyCharts() throws IOException, InterruptedException {
        Map<String, Integer> charts = new LinkedHashMap<>();
        for (SupersetAssets.Dashboard definition : SupersetAssets.DASHBOARDS) {
            String path = "dashboard/" + definition.slug();
            JsonNode dashboard = json("GET", path, null).path(RESULT);
            if (!dashboard.path("published").asBoolean())
                throw new IOException("Bank dashboard is not published: " + definition.slug());
            JsonNode related = json("GET", path + "/charts", null).path(RESULT);
            if (!related.isArray()) throw new IOException("Invalid Superset dashboard charts");
            Set<String> expected = new HashSet<>();
            definition.charts().forEach(chart -> expected.add(chart.title));
            Set<String> found = new HashSet<>();
            for (JsonNode chart : related) {
                String title = chart.path("slice_name").asText();
                int id = chart.path("id").asInt();
                if (!expected.contains(title) || !found.add(title) || id <= 0)
                    throw new IOException("Unexpected Superset dashboard chart association");
                Integer previous = charts.putIfAbsent(title, id);
                if (previous != null && previous != id)
                    throw new IOException("Shared Superset chart identity does not match");
            }
            if (!found.equals(expected))
                throw new IOException("Superset dashboard charts are missing");
        }
        if (new HashSet<>(charts.values()).size() != SupersetAssets.Chart.values().length)
            throw new IOException("Expected exactly six distinct charts");
        for (int id : charts.values()) {
            JsonNode result = json("GET", "chart/" + id + "/data/", null);
            if (!result.path(RESULT).isArray() || result.path(RESULT).isEmpty())
                throw new IOException("Superset chart query is missing");
            for (JsonNode query : result.path(RESULT)) {
                if (!query.path("error").isMissingNode() && !query.path("error").isNull())
                    throw new IOException("Superset chart query failed");
            }
        }
    }

    private List<JsonNode> list(String path) throws IOException, InterruptedException {
        List<JsonNode> rows = new ArrayList<>();
        // У этого стенда десятки security permissions; конечный предел защищает job от чужой
        // огромной metadata-БД.
        for (int page = 0; page < 20; page++) {
            String query =
                    URLEncoder.encode("(page:" + page + ",page_size:100)", StandardCharsets.UTF_8);
            JsonNode response = json("GET", path + "?q=" + query, null);
            JsonNode result = response.path(RESULT);
            if (!result.isArray()) throw new IOException("Invalid Superset list response");
            result.forEach(rows::add);
            if (rows.size() >= response.path("count").asInt()) return rows;
        }
        throw new IOException("Superset metadata listing exceeds limit");
    }

    private JsonNode json(String method, String path, Object body)
            throws IOException, InterruptedException {
        return request(
                method, path, JSON_TYPE, body == null ? null : mapper.writeValueAsBytes(body));
    }

    private JsonNode request(String method, String path, String type, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(endpoint.resolve(API + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", JSON_TYPE);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (csrf != null) {
            request.header("X-CSRFToken", csrf);
            // Flask-WTF проверяет также HTTPS Referer; проверку происхождения не отключаем.
            request.header("Referer", endpoint.resolve("/").toString());
        }
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (body != null) request.header("Content-Type", type);
        HttpResponse<byte[]> response = http.send(request.build(), ignored -> new LimitedBody());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // В vendor error-body могут оказаться SQL URI и секреты: сохраняем только HTTP-код.
            throw new IOException("Superset API returned HTTP " + response.statusCode());
        }
        return response.body().length == 0
                ? mapper.createObjectNode()
                : mapper.readTree(response.body());
    }

    private static void part(ByteArrayOutputStream body, String boundary, String name, String value)
            throws IOException {
        body.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + name
                                + "\"\r\n\r\n"
                                + value
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

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
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Superset response exceeds limit"));
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
    }
}
