package dev.principalwater.store.service;

import dev.principalwater.store.Browser;
import dev.principalwater.store.SmtpStub;
import dev.principalwater.store.StoreIntegrationTestBase;
import dev.principalwater.store.config.SellerBootstrap;
import dev.principalwater.store.config.SellerProperties;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreUser;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class PasswordRecoveryIntegrationTest extends StoreIntegrationTestBase {
    private static final String PASSWORD = "correct horse recovery pass";
    private static final String NEW_PASSWORD = "brand new horse battery staple";
    private static final String RESET = "/account/recovery/reset";
    private static final Pattern LINK = Pattern.compile("https://shop\\.example\\.test(/account/recovery/reset\\?token=([A-Za-z0-9_-]{43}))");
    @Autowired private StoreUserRepository users;
    @Autowired private RecoveryService recovery;
    @Autowired private PasswordEncoder passwords;
    @Autowired private MeterRegistry metrics;
    @Autowired private JavaMailSenderImpl mailSender;
    @Autowired private CartRepository carts;
    @Autowired private TransactionalOperator transactions;
    @Autowired @Qualifier("passwordScheduler") private Scheduler passwordScheduler;
    private WebTestClient web;

    @BeforeEach
    void client() {
        web = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(TIMEOUT).build();
    }

    @Test
    void knownAndUnknownAddressesGetTheSameReplyAndOnlyTheOwnerGetsAConfiguredLinkOnce() throws Exception {
        register("alice.recovery");
        // Заголовок Host подделан: ссылка в письме всё равно строится из настройки, а не из запроса.
        var forged = WebTestClient.bindToApplicationContext(context).configureClient()
                .baseUrl("http://evil.example").responseTimeout(TIMEOUT).build();
        var visitor = new Browser(forged);
        visitor.get("/account/recovery", HttpStatus.OK);
        var unknown = visitor.post("/account/recovery", Map.of("email", "nobody@example.test"), true, HttpStatus.SEE_OTHER);
        var known = visitor.post("/account/recovery", Map.of("email", "Alice.Recovery@Example.Test"), true, HttpStatus.SEE_OTHER);
        assertEquals("/account/recovery?sent", known.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals(unknown.getResponseHeaders().getFirst(HttpHeaders.LOCATION), known.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals(unknown.getResponseBody(), known.getResponseBody());
        assertTrue(visitor.get("/account/recovery?sent", HttpStatus.OK).contains("Если эта почта зарегистрирована"));

        var mail = awaitMails(1).getFirst();
        assertEquals(List.of("<alice.recovery@example.test>"), mail.recipients());
        assertEquals("Восстановление доступа · Мастерская", mail.message().getSubject());
        assertEquals("no-reply@example.test", ((InternetAddress) mail.message().getFrom()[0]).getAddress());
        String body = mail.body();
        assertFalse(body.contains("evil.example"));
        String token = link(body).group(2);
        var stored = await(database.sql("SELECT token_hash, requested_at, expires_at FROM password_resets")
                .map(row -> new StoredReset(row.get("token_hash", String.class), row.get("requested_at", OffsetDateTime.class),
                        row.get("expires_at", OffsetDateTime.class))).one());
        assertEquals(sha256(token), stored.tokenHash(), "Only the SHA-256 of the token may be stored");
        assertEquals(Duration.ofMinutes(15), Duration.between(stored.requestedAt(), stored.expiresAt()));

        // Повторный запрос в течение минуты не выпускает новый токен и не отправляет второе письмо.
        double throttled = metrics.counter("store.recovery.requests", "outcome", "throttled").count();
        visitor.post("/account/recovery", Map.of("email", "alice.recovery@example.test"), true, HttpStatus.SEE_OTHER);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                metrics.counter("store.recovery.requests", "outcome", "throttled").count() > throttled);
        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until(() -> SmtpStub.MAILS.size() == 1);
        assertEquals(stored.tokenHash(), storedHash());
    }

    @Test
    void resetPageIsPrivateAndTheNewPasswordReplacesTheOldOneExactlyOnceWithoutSigningIn() throws Exception {
        register("carol.recovery");
        String token = issuedToken("carol.recovery@example.test");
        var visitor = new Browser(web);
        String page = visitor.get(RESET + "?token=" + token, HttpStatus.OK);
        assertTrue(visitor.headers.getCacheControl().contains("no-store"));
        assertEquals("no-referrer", visitor.headers.getFirst("Referrer-Policy"));
        assertTrue(page.contains(token), "The form must carry the token to its POST");

        visitor.post(RESET, Map.of("token", token, "password", NEW_PASSWORD), false, HttpStatus.FORBIDDEN);
        visitor.post(RESET, Map.of("token", token, "password", "short"), true, HttpStatus.BAD_REQUEST);
        visitor.post(RESET, Map.of("token", token, "password", "я".repeat(40)), true, HttpStatus.BAD_REQUEST);
        assertEquals(1L, pendingResets(), "Rejected input must not spend the token");
        assertEquals(0L, authVersion("carol.recovery"));

        var done = visitor.post(RESET, Map.of("token", token, "password", NEW_PASSWORD), true, HttpStatus.SEE_OTHER);
        assertEquals("/account/login?passwordReset", done.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        visitor.get("/cabinet", HttpStatus.FOUND);
        assertEquals(0L, pendingResets());
        assertEquals(1L, authVersion("carol.recovery"));
        assertTrue(passwords.matches(NEW_PASSWORD, await(users.findByUsername("carol.recovery")).passwordHash()));
        assertTrue(visitor.get("/account/login?passwordReset", HttpStatus.OK).contains("Пароль изменён"));

        visitor.get("/account/login", HttpStatus.OK);
        var rejected = visitor.post("/account/login", Map.of("username", "carol.recovery", "password", PASSWORD), true, HttpStatus.FOUND);
        assertEquals("/account/login?error", rejected.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        login(visitor, "carol.recovery", NEW_PASSWORD);
        assertTrue(visitor.get("/cabinet", HttpStatus.OK).contains("carol.recovery"));

        var replay = new Browser(web);
        replay.get(RESET + "?token=" + token, HttpStatus.OK);
        assertTrue(replay.post(RESET, Map.of("token", token, "password", NEW_PASSWORD), true, HttpStatus.BAD_REQUEST)
                .getResponseBody().contains("Ссылка недействительна"));
        assertEquals(1L, authVersion("carol.recovery"), "A replayed token must not change the password again");
    }

    @Test
    void passwordResetEndsEverySessionOfTheAccountButLeavesOtherAccountsSignedIn() throws Exception {
        register("dave.recovery");
        register("erin.recovery");
        var first = signedIn("dave.recovery");
        var second = signedIn("dave.recovery");
        var other = signedIn("erin.recovery");
        first.get("/cabinet", HttpStatus.OK);
        String token = issuedToken("dave.recovery@example.test");

        recovery.reset(token, NEW_PASSWORD).block(TIMEOUT);

        first.get("/cabinet", HttpStatus.FOUND);
        second.get("/cabinet", HttpStatus.FOUND);
        // Устаревшая сессия анонимна и на публичных страницах: ни корзины, ни меню аккаунта.
        String catalog = first.get("/items", HttpStatus.OK);
        assertFalse(catalog.contains("href=\"/cabinet\""));
        assertTrue(catalog.contains("href=\"/account/login\""));
        other.get("/cabinet", HttpStatus.OK);
        assertEquals(0L, authVersion("erin.recovery"));
        // Тот же браузер входит заново с новым паролем: вход поворачивает сессию, а не воскрешает старую.
        first.get("/account/login", HttpStatus.OK);
        login(first, "dave.recovery", NEW_PASSWORD);
        first.get("/cabinet", HttpStatus.OK);
        second.get("/cabinet", HttpStatus.FOUND);
    }

    @Test
    void expiredMalformedAndForeignTokensAreRejectedWithoutTouchingAnotherAccount() throws Exception {
        register("frank.recovery");
        register("grace.recovery");
        String expired = issuedToken("frank.recovery@example.test");
        String valid = issuedToken("grace.recovery@example.test");
        // Время жизни проверяется условием удаления: истёкший токен не потребляется, даже если его знают.
        await(database.sql("UPDATE password_resets SET expires_at = :past WHERE token_hash = :hash")
                .bind("past", Instant.now().minusSeconds(60)).bind("hash", sha256(expired)).fetch().rowsUpdated());
        var visitor = new Browser(web);
        visitor.get(RESET + "?token=" + valid, HttpStatus.OK);

        for (String token : List.of(expired, "not-a-token", "A".repeat(43), valid.substring(1) + "A")) {
            var rejected = visitor.post(RESET, Map.of("token", token, "password", NEW_PASSWORD), true, HttpStatus.BAD_REQUEST);
            assertTrue(rejected.getResponseBody().contains("Ссылка недействительна"));
        }
        assertEquals(0L, authVersion("frank.recovery"));
        assertEquals(0L, authVersion("grace.recovery"));
        assertEquals(2L, pendingResets());

        visitor.post(RESET, Map.of("token", valid, "password", NEW_PASSWORD), true, HttpStatus.SEE_OTHER);
        assertEquals(1L, authVersion("grace.recovery"));
        assertEquals(0L, authVersion("frank.recovery"), "A token belongs to exactly one account");
        assertTrue(passwords.matches(PASSWORD, await(users.findByUsername("frank.recovery")).passwordHash()));
    }

    @Test
    void concurrentSubmissionsOfOneTokenCommitExactlyOnePasswordAndConcurrentRequestsIssueOneToken() throws Exception {
        register("heidi.recovery");
        Flux.range(0, 6).flatMap(attempt -> recovery.request("heidi.recovery@example.test"), 6).then().block(TIMEOUT);
        var mails = awaitMails(1);
        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until(() -> SmtpStub.MAILS.size() == 1);
        assertEquals(1L, pendingResets(), "The per-account cooldown admits one token among concurrent requests");
        String token = link(mails.getFirst().body()).group(2);

        List<String> candidates = List.of("concurrent password 0", "concurrent password 1",
                "concurrent password 2", "concurrent password 3");
        var outcomes = Flux.fromIterable(candidates)
                .flatMap(candidate -> recovery.reset(token, candidate).materialize(), candidates.size())
                .collectList().block(TIMEOUT);
        assertEquals(1, outcomes.stream().filter(Signal::isOnComplete).count());
        outcomes.stream().filter(Signal::isOnError).forEach(signal ->
                assertEquals(StoreError.INVALID_RECOVERY_TOKEN, ((StoreException) signal.getThrowable()).getError()));
        assertEquals(1L, authVersion("heidi.recovery"));
        String hash = await(users.findByUsername("heidi.recovery")).passwordHash();
        assertEquals(1, candidates.stream().filter(candidate -> passwords.matches(candidate, hash)).count(),
                "Exactly one submitted password must have been committed");
    }

    @Test
    void failedDeliveryKeepsTheReplyGenericCountsTheFailureAndLogsNoPersonalData(CapturedOutput output) throws Exception {
        register("ivan.recovery");
        SmtpStub.REJECT.set(true);
        double failed = metrics.counter("store.recovery.mail", "outcome", "failed").count();
        var visitor = new Browser(web);
        visitor.get("/account/recovery", HttpStatus.OK);
        var reply = visitor.post("/account/recovery", Map.of("email", "ivan.recovery@example.test"), true, HttpStatus.SEE_OTHER);
        assertEquals("/account/recovery?sent", reply.getResponseHeaders().getFirst(HttpHeaders.LOCATION));

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> metrics.counter("store.recovery.mail", "outcome", "failed").count() > failed);
        assertTrue(SmtpStub.MAILS.isEmpty());
        assertTrue(output.getAll().contains("Password recovery mail was not delivered"));
        assertFalse(output.getAll().contains("ivan.recovery"), "Delivery failures must not log the recipient");
        assertFalse(Pattern.compile("token=[A-Za-z0-9_-]{43}").matcher(output.getAll()).find(), "Reset links must never be logged");
    }

    @Test
    void theSellerContactFromConfigurationEnablesRecoveryWithoutOverwritingAnExistingContact() throws Exception {
        var bootstrap = new SellerBootstrap(new SellerProperties("boot.seller", PASSWORD, "Boot.Seller@Example.Test"), users, carts,
                passwords, passwordScheduler, transactions);
        bootstrap.run(null);
        assertEquals("boot.seller@example.test", await(users.findByUsername("boot.seller")).email());
        // Продавец без контакта (создан до восстановления) получает его из конфигурации.
        execute("UPDATE store_users SET email = NULL WHERE username = 'boot.seller'");
        bootstrap.run(null);
        assertEquals("boot.seller@example.test", await(users.findByUsername("boot.seller")).email());
        // Уже внесённый контакт не заменяется: меняет его оператор, а не перезапуск с другой настройкой.
        new SellerBootstrap(new SellerProperties("boot.seller", PASSWORD, "other@example.test"), users, carts,
                passwords, passwordScheduler, transactions).run(null);
        assertEquals("boot.seller@example.test", await(users.findByUsername("boot.seller")).email());
        recovery.request("boot.seller@example.test").block(TIMEOUT);
        assertEquals(List.of("<boot.seller@example.test>"), awaitMails(1).getFirst().recipients());
    }

    @Test
    void mailTransportAlwaysHasFiniteConnectReadAndWriteTimeouts() {
        for (String key : Set.of("mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout")) {
            int timeout = Integer.parseInt(mailSender.getJavaMailProperties().getProperty(key));
            assertTrue(timeout > 0 && timeout <= 10_000, key + " must be a short finite timeout");
        }
    }

    private void register(String username) {
        var browser = new Browser(web);
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", username, "displayName", "Тест", "email", username + "@example.test",
                "password", PASSWORD), true, HttpStatus.SEE_OTHER);
    }

    private Browser signedIn(String username) {
        var browser = new Browser(web);
        browser.get("/account/login", HttpStatus.OK);
        login(browser, username, PASSWORD);
        return browser;
    }

    private void login(Browser browser, String username, String password) {
        var result = browser.post("/account/login", Map.of("username", username, "password", password), true, HttpStatus.FOUND);
        assertEquals("/cabinet", result.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        browser.csrf = null;
    }

    // Запрос восстановления через сервис: одно письмо на вызов, токен читается из настоящего письма.
    private String issuedToken(String email) throws Exception {
        int before = SmtpStub.MAILS.size();
        recovery.request(email).block(TIMEOUT);
        var mails = awaitMails(before + 1);
        return link(mails.get(before).body()).group(2);
    }

    private List<SmtpStub.Mail> awaitMails(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> SmtpStub.MAILS.size() >= count);
        return List.copyOf(SmtpStub.MAILS);
    }

    private static Matcher link(String body) {
        var link = LINK.matcher(body);
        assertTrue(link.find(), "The message must contain the configured reset link");
        return link;
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private long pendingResets() {
        return await(database.sql("SELECT COUNT(*) AS total FROM password_resets")
                .map(row -> ((Number) row.get("total")).longValue()).one());
    }

    private String storedHash() {
        return await(database.sql("SELECT token_hash FROM password_resets").map(row -> row.get("token_hash", String.class)).one());
    }

    private long authVersion(String username) {
        StoreUser user = await(users.findByUsername(username));
        return await(database.sql("SELECT auth_version FROM store_users WHERE id = :id").bind("id", user.id())
                .map(row -> ((Number) row.get("auth_version")).longValue()).one());
    }

    private record StoredReset(String tokenHash, OffsetDateTime requestedAt, OffsetDateTime expiresAt) { }
}
