package dev.principalwater.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Реальный HTTP fixture сохраняет списание даже при потере ответа, как независимый сервис. */
public final class PaymentsStub {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<UUID, Long> BALANCES = new ConcurrentHashMap<>();
    private static final Map<String, String> RECEIPTS = new ConcurrentHashMap<>();
    public static final AtomicInteger API_REQUESTS = new AtomicInteger();
    public static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    public static final AtomicBoolean TOKEN_UNAVAILABLE = new AtomicBoolean();
    public static final AtomicBoolean REJECT_TOKEN = new AtomicBoolean();
    private static final String ACCESS_TOKEN = "fixture-payments-access-token";
    public static final AtomicBoolean UNAVAILABLE = new AtomicBoolean();
    public static final Set<String> UNAVAILABLE_KEYS = ConcurrentHashMap.newKeySet();
    public static final AtomicBoolean LOSE_RESPONSE = new AtomicBoolean();
    public static final AtomicInteger DEBITS = new AtomicInteger();
    public static final DisposableServer SERVER = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
            .post("/oauth/token", (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                TOKEN_REQUESTS.incrementAndGet();
                if (TOKEN_UNAVAILABLE.get()) { return response.status(503).send().then(); }
                String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "storefront:fixture-client-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!expected.equals(request.requestHeaders().get("Authorization"))
                        || !body.contains("grant_type=client_credentials")) {
                    return response.status(401).send().then();
                }
                return response.header("Content-Type", "application/json").sendString(Mono.just(
                        "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                                + "\"scope\":\"payments.read payments.write\"}")).then();
            }))
            .get("/api/v1/accounts/{accountId}/balance", (request, response) -> {
                API_REQUESTS.incrementAndGet();
                if (!authorized(request) || REJECT_TOKEN.compareAndSet(true, false)) {
                    return response.status(401).header("WWW-Authenticate", "Bearer error=\"invalid_token\"").send();
                }
                if (UNAVAILABLE.get()) { return response.status(503).send(); }
                UUID id = UUID.fromString(request.param("accountId"));
                return response.header("Content-Type", "application/json").sendString(Mono.just(
                        "{\"accountId\":\"" + id + "\",\"balanceMinor\":" + balance(id) + ",\"currency\":\"RUB\"}"));
            })
            .post("/api/v1/payments", (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                if (!authorized(request)) { return response.status(401).send().then(); }
                String key = request.requestHeaders().get("Idempotency-Key");
                if (UNAVAILABLE.get() || UNAVAILABLE_KEYS.contains(key)) { return response.status(503).send().then(); }
                String receipt = apply(key, body);
                if (LOSE_RESPONSE.compareAndSet(true, false)) { return response.status(503).send().then(); }
                return response.status(receipt.contains("DECLINED") ? 402 : 200)
                        .header("Content-Type", "application/json").sendString(Mono.just(receipt)).then();
            }))).bindNow();
    private PaymentsStub() { }
    public static long balance(UUID id) { return BALANCES.getOrDefault(id, 1_000_000_000L); }
    public static void funds(UUID id, long minor) { BALANCES.put(id, minor); }
    public static void reset() {
        BALANCES.clear(); RECEIPTS.clear(); UNAVAILABLE_KEYS.clear();
        TOKEN_REQUESTS.set(0); API_REQUESTS.set(0); TOKEN_UNAVAILABLE.set(false); REJECT_TOKEN.set(false);
        DEBITS.set(0); UNAVAILABLE.set(false); LOSE_RESPONSE.set(false);
    }
    private static boolean authorized(reactor.netty.http.server.HttpServerRequest request) {
        return ("Bearer " + ACCESS_TOKEN).equals(request.requestHeaders().get("Authorization"));
    }
    private static synchronized String apply(String key, String body) {
        return RECEIPTS.computeIfAbsent(key, ignored -> {
            try {
                JsonNode request = JSON.readTree(body);
                UUID account = UUID.fromString(request.get("accountId").asText());
                long amount = request.get("amountMinor").asLong();
                long before = balance(account);
                boolean paid = before >= amount;
                if (paid) { BALANCES.put(account, Math.subtractExact(before, amount)); DEBITS.incrementAndGet(); }
                return JSON.writeValueAsString(Map.of("paymentId", UUID.randomUUID(), "accountId", account,
                        "referenceId", request.get("referenceId").asText(), "amountMinor", amount,
                        "balanceMinor", balance(account), "currency", "RUB", "status", paid ? "SUCCEEDED" : "DECLINED"));
            } catch (java.io.IOException failure) { throw new IllegalArgumentException(failure); }
        });
    }
}
