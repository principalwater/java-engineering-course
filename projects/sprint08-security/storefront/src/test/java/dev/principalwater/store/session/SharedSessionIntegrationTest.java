package dev.principalwater.store.session;

import dev.principalwater.store.Browser;
import dev.principalwater.store.SmtpStub;
import dev.principalwater.store.StoreApplication;
import dev.principalwater.store.StoreIntegrationTestBase;
import dev.principalwater.store.TcpProxy;
import dev.principalwater.store.service.RecoveryService;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Две реплики приложения делят отдельный настоящий Redis (STORE_TEST_SESSIONS_URL); третья реплика ходит к нему через
 * прокси, который умеет зависать. Тест не входит в обычный набор: ему нужен изолированный Redis.
 */
@ActiveProfiles("shared-sessions")
class SharedSessionIntegrationTest extends StoreIntegrationTestBase {
    private static final String PASSWORD = "correct horse shared pass";
    private static final String NEW_PASSWORD = "brand new horse battery staple";
    private static final String NAMESPACE = "store:session:it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final Pattern RESET_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");
    private static final URI STORE = storeUri();
    private static ConfigurableApplicationContext replica;
    private static ConfigurableApplicationContext unreachable;
    private static TcpProxy proxy;
    private WebTestClient first;
    private WebTestClient second;
    private WebTestClient hung;
    private String sessionCookie;

    @DynamicPropertySource
    static void sessions(DynamicPropertyRegistry registry) {
        registry.add("store.sessions.host", STORE::getHost);
        registry.add("store.sessions.port", STORE::getPort);
        registry.add("store.sessions.username", () -> credentials()[0]);
        registry.add("store.sessions.password", () -> credentials()[1]);
        registry.add("store.sessions.namespace", () -> NAMESPACE);
    }

    @BeforeEach
    void replicas() throws IOException {
        if (replica == null) {
            replica = start(STORE.getHost(), STORE.getPort());
            proxy = new TcpProxy(STORE.getHost(), STORE.getPort());
            unreachable = start("127.0.0.1", proxy.port());
        }
        first = client(context);
        second = client(replica);
        hung = client(unreachable);
        sessionCookie = context.getEnvironment().getProperty("server.reactive.session.cookie.name", "SESSION");
    }

    @AfterAll
    static void stopReplicas() throws IOException {
        for (var application : new ConfigurableApplicationContext[] {unreachable, replica}) {
            if (application != null) {
                application.close();
            }
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    @Test
    void aSessionStartedOnOneReplicaServesTheOtherAndLogoutEndsItEverywhere() {
        register("uma.shared");
        // Продавец нужен для уведомления в сессии; роль назначается вне публичной регистрации, до входа.
        execute("UPDATE store_users SET role = 'SELLER' WHERE username = 'uma.shared'");
        var browser = new Browser(first);
        browser.get("/account/login", HttpStatus.OK);
        String guestToken = browser.csrf;
        String guestSession = browser.cookies.get(sessionCookie);

        // Гостевой CSRF-токен создан первой репликой, а вход принимает вторая: токен и сессия лежат только в Redis.
        browser.using(second);
        var signedIn = browser.post("/account/login", Map.of("username", "uma.shared", "password", PASSWORD), true, HttpStatus.FOUND);
        assertEquals("/cabinet", signedIn.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        String session = browser.cookies.get(sessionCookie);
        browser.csrf = null;
        assertNotEquals(guestSession, session, "Login must rotate the session ID");
        assertTrue(browser.using(first).get("/cabinet", HttpStatus.OK).contains("uma.shared"));
        assertTrue(browser.using(second).get("/cabinet", HttpStatus.OK).contains("uma.shared"));
        assertStoredSafely(session);

        // Одноразовое уведомление лежит в сессии обычной строкой: запись видна другой реплике, удаление — тоже.
        browser.using(second).post("/catalog/demo", Map.of(), true, HttpStatus.SEE_OTHER);
        assertTrue(browser.using(first).get("/items", HttpStatus.OK).contains("Каталог мастерской готов"));
        assertFalse(browser.using(second).get("/items", HttpStatus.OK).contains("Каталог мастерской готов"));

        // Токен гостевой сессии после входа недействителен на любой реплике, свежий токен принимается любой.
        browser.csrf = guestToken;
        browser.using(first).post("/account/logout", Map.of(), true, HttpStatus.FORBIDDEN);
        browser.csrf = null;
        browser.get("/account/logout", HttpStatus.OK);
        browser.using(second).post("/account/logout", Map.of(), true, HttpStatus.FOUND);

        for (var replicaClient : List.of(first, second)) {
            var stolen = new Browser(replicaClient);
            stolen.cookies.put(sessionCookie, session);
            stolen.get("/cabinet", HttpStatus.FOUND);
        }
        assertEquals(Boolean.FALSE, redis().hasKey(sessionKey(session)).block(TIMEOUT), "Logout must delete the stored session");
    }

    @Test
    void aPasswordResetOnOneReplicaEndsTheSessionsHeldOnEveryReplica() throws Exception {
        register("vera.shared");
        register("wade.shared");
        var onFirst = signedIn(first, "vera.shared");
        var onSecond = signedIn(second, "vera.shared");
        var bystander = signedIn(first, "wade.shared");
        for (var browser : List.of(onFirst, onSecond)) {
            browser.using(first).get("/cabinet", HttpStatus.OK);
            browser.using(second).get("/cabinet", HttpStatus.OK);
        }

        context.getBean(RecoveryService.class).request("vera.shared@example.test").block(TIMEOUT);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !SmtpStub.MAILS.isEmpty());
        var token = RESET_TOKEN.matcher(SmtpStub.MAILS.getFirst().body());
        assertTrue(token.find());
        var visitor = new Browser(second);
        visitor.get("/account/recovery/reset?token=" + token.group(1), HttpStatus.OK);
        visitor.post("/account/recovery/reset", Map.of("token", token.group(1), "password", NEW_PASSWORD), true, HttpStatus.SEE_OTHER);

        for (var browser : List.of(onFirst, onSecond)) {
            browser.using(first).get("/cabinet", HttpStatus.FOUND);
            browser.using(second).get("/cabinet", HttpStatus.FOUND);
        }
        bystander.using(second).get("/cabinet", HttpStatus.OK);
        var again = new Browser(first);
        again.get("/account/login", HttpStatus.OK);
        again.using(second).post("/account/login", Map.of("username", "vera.shared", "password", NEW_PASSWORD), true, HttpStatus.FOUND);
        again.using(first).get("/cabinet", HttpStatus.OK);
    }

    @Test
    void aHungSessionStoreFailsClosedWithinTheTimeoutsAndTheNodeRecoversWhenItAnswersAgain() {
        register("xena.shared");
        var browser = signedIn(first, "xena.shared");
        String session = browser.cookies.get(sessionCookie);

        // Хранилище принимает соединение и молчит: рукопожатие не завершается.
        proxy.mode(TcpProxy.Mode.BLACKHOLE);
        assertUnavailable(session);
        proxy.mode(TcpProxy.Mode.FORWARD);
        assertServes(session);

        // Соединение открыто, но ответы перестали приходить: команды обрываются по таймауту чтения.
        proxy.mode(TcpProxy.Mode.STALL);
        assertUnavailable(session);
        proxy.mode(TcpProxy.Mode.FORWARD);
        assertServes(session);

        // Сервер остановлен: открытое соединение оборвано, новые отвергаются, команды отклоняются без ожидания.
        proxy.mode(TcpProxy.Mode.CUT);
        assertUnavailable(session);
        proxy.mode(TcpProxy.Mode.FORWARD);
        assertServes(session);
        assertTrue(browser.using(first).get("/cabinet", HttpStatus.OK).contains("xena.shared"));
    }

    // Отказ быстрый и без локальной замены: ни страницы входа, ни cookie, ни доступа по действительной cookie другой реплики.
    private void assertUnavailable(String session) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var guest = new Browser(hung);
            long started = System.nanoTime();
            guest.get("/account/login", HttpStatus.SERVICE_UNAVAILABLE);
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0, "The outage must be bounded");
            assertTrue(guest.cookies.isEmpty(), "No session may be created locally while the store is unavailable");
            assertNull(guest.headers.get(HttpHeaders.SET_COOKIE));
        }
        var holder = new Browser(hung);
        holder.cookies.put(sessionCookie, session);
        holder.get("/cabinet", HttpStatus.SERVICE_UNAVAILABLE);
        assertNull(holder.headers.get(HttpHeaders.SET_COOKIE));
        hung.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);
    }

    private void assertServes(String session) {
        var holder = new Browser(hung);
        holder.cookies.put(sessionCookie, session);
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            assertTrue(holder.get("/cabinet", HttpStatus.OK).contains("xena.shared"));
            hung.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        });
    }

    private void assertStoredSafely(String session) {
        var stored = redis().opsForHash().entries(sessionKey(session)).collectMap(entry -> String.valueOf(entry.getKey()),
                entry -> String.valueOf(entry.getValue())).block(TIMEOUT);
        assertNotNull(stored);
        String context = stored.get("sessionAttr:SPRING_SECURITY_CONTEXT");
        assertNotNull(context, "The authenticated context must be stored in Redis");
        assertTrue(context.contains("\"@class\":\"dev.principalwater.store.model.StoreUserDetails\""));
        assertTrue(context.contains("\"username\":\"uma.shared\""));
        for (String value : stored.values()) {
            assertFalse(value.contains("$2a$") || value.contains(PASSWORD) || value.contains("uma.shared@example.test"),
                    "Neither the hash, the plaintext password nor the contact address belongs in the session store");
            assertFalse(value.startsWith("¬í"), "Java serialization must not be used");
        }
        Long millis = redis().getExpire(sessionKey(session)).map(Duration::toMillis).block(TIMEOUT);
        assertNotNull(millis);
        assertTrue(millis > 0 && millis <= Duration.ofMinutes(30).toMillis(), "Sessions must expire with the idle timeout");
    }

    private ReactiveStringRedisTemplate redis() {
        return new ReactiveStringRedisTemplate(context.getBean("sessionConnectionFactory", LettuceConnectionFactory.class));
    }

    private static String sessionKey(String session) {
        return NAMESPACE + ":sessions:" + session;
    }

    private void register(String username) {
        var browser = new Browser(first);
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", username, "displayName", "Тест", "email", username + "@example.test",
                "password", PASSWORD), true, HttpStatus.SEE_OTHER);
    }

    private Browser signedIn(WebTestClient client, String username) {
        var browser = new Browser(client);
        browser.get("/account/login", HttpStatus.OK);
        browser.post("/account/login", Map.of("username", username, "password", PASSWORD), true, HttpStatus.FOUND);
        browser.csrf = null;
        return browser;
    }

    private WebTestClient client(ApplicationContext application) {
        return WebTestClient.bindToApplicationContext(application).configureClient().responseTimeout(TIMEOUT).build();
    }

    // Вторая реплика того же приложения: общие база и заглушки, но собственный контекст, кэши и клиент Redis.
    private ConfigurableApplicationContext start(String host, int port) {
        var environment = context.getEnvironment();
        return new SpringApplicationBuilder(StoreApplication.class).profiles("test", "shared-sessions").run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--store.payments.url=" + environment.getRequiredProperty("store.payments.url"),
                "--spring.security.oauth2.client.provider.payments.token-uri="
                        + environment.getRequiredProperty("spring.security.oauth2.client.provider.payments.token-uri"),
                "--spring.mail.host=" + environment.getRequiredProperty("spring.mail.host"),
                "--spring.mail.port=" + environment.getRequiredProperty("spring.mail.port"),
                "--store.recovery.public-base-url=" + environment.getRequiredProperty("store.recovery.public-base-url"),
                "--store.recovery.sender=" + environment.getRequiredProperty("store.recovery.sender"),
                "--store.sessions.host=" + host, "--store.sessions.port=" + port,
                "--store.sessions.username=" + credentials()[0], "--store.sessions.password=" + credentials()[1],
                "--store.sessions.namespace=" + NAMESPACE);
    }

    private static URI storeUri() {
        String url = System.getenv("STORE_TEST_SESSIONS_URL");
        if (url == null || !url.startsWith("redis://")) {
            throw new IllegalStateException("Session integration tests require an isolated STORE_TEST_SESSIONS_URL");
        }
        return URI.create(url);
    }

    private static String[] credentials() {
        String info = STORE.getUserInfo();
        int separator = info == null ? -1 : info.indexOf(':');
        if (separator < 0) {
            throw new IllegalStateException("STORE_TEST_SESSIONS_URL must carry redis://user:password@host:port");
        }
        return new String[] {info.substring(0, separator), info.substring(separator + 1)};
    }
}
