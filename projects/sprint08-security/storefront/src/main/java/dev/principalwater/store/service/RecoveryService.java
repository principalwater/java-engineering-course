package dev.principalwater.store.service;

import dev.principalwater.store.config.RecoveryProperties;
import dev.principalwater.store.dao.PasswordResetQueries;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.runtime.RuntimeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Service
public class RecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration COOLDOWN = Duration.ofMinutes(1);
    // Минимальная задержка сглаживает быстрые ответы; SMTP не занимает время HTTP-запроса.
    private static final Duration RESPONSE_FLOOR = Duration.ofMillis(300);
    // Пауза держит слот общего ограничения нагрузки: без собственного предела поток запросов закрыл бы весь магазин.
    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final String REQUEST_METER = "store.recovery.requests";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RecoveryProperties properties;
    private final RecoveryMailer mailer;
    private final StoreUserRepository users;
    private final PasswordResetQueries resets;
    private final PasswordEncoder passwords;
    private final Scheduler passwordScheduler;
    private final TransactionalOperator transactions;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicInteger requesting = new AtomicInteger();
    private final int requestLimit;

    public RecoveryService(RecoveryProperties properties, RecoveryMailer mailer, StoreUserRepository users,
                           PasswordResetQueries resets, PasswordEncoder passwords, Scheduler passwordScheduler,
                           TransactionalOperator transactions, Clock clock, MeterRegistry metrics, RuntimeProperties budget) {
        this.properties = properties;
        this.mailer = mailer;
        this.users = users;
        this.resets = resets;
        this.passwords = passwords;
        this.passwordScheduler = passwordScheduler;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;
        requestLimit = Math.min(MAX_CONCURRENT_REQUESTS, Math.max(1, budget.maxInFlight() / 4));
        if (!available()) {
            LOG.info("Password recovery is unavailable: enable store.recovery, set store.recovery.public-base-url "
                    + "and store.recovery.sender, and configure spring.mail.host");
        }
    }

    public boolean available() {
        return properties.enabled() && !properties.publicBaseUrl().isEmpty() && mailer.ready();
    }

    // Один и тот же результат для любого адреса: письмо уходит только владельцу, клиент ничего не узнаёт об аккаунте.
    public Mono<Void> request(String email) {
        return Mono.defer(() -> {
            if (requesting.incrementAndGet() > requestLimit) {
                requesting.decrementAndGet();
                return Mono.error(new StoreException(StoreError.RECOVERY_BUSY));
            }
            Mono<Void> issuing = users.findByEmail(AccountRules.normalizeEmail(email))
                    .flatMap(user -> issue(user).thenReturn(user))
                    .switchIfEmpty(Mono.fromRunnable(() -> metrics.counter(REQUEST_METER, "outcome", "unknown").increment()))
                    .then();
            return Mono.when(issuing, Mono.delay(RESPONSE_FLOOR)).doFinally(signal -> requesting.decrementAndGet());
        });
    }

    // BCrypt выполняется до транзакции и вне event loop; в транзакции остаются два быстрых оператора.
    public Mono<Void> reset(String token, String password) {
        if (token == null || !token.matches(AccountRules.RECOVERY_TOKEN_PATTERN)) {
            return Mono.error(new StoreException(StoreError.INVALID_RECOVERY_TOKEN));
        }
        if (!AccountRules.validPassword(password)) {
            return Mono.error(new StoreException(StoreError.INVALID_ACCOUNT));
        }
        String tokenHash = hash(token);
        return resets.findOwner(tokenHash, clock.instant())
                .switchIfEmpty(Mono.error(new StoreException(StoreError.INVALID_RECOVERY_TOKEN)))
                .flatMap(userId -> Mono.fromCallable(() -> passwords.encode(password)).subscribeOn(passwordScheduler)
                        .flatMap(encoded -> replacePassword(userId, tokenHash, encoded)));
    }

    private Mono<Void> issue(StoreUser user) {
        String token = newToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TOKEN_TTL);
        String tokenHash = hash(token);
        return resets.reissue(user.id(), tokenHash, now, expiresAt, now.minus(COOLDOWN))
                .flatMap(reissued -> reissued ? Mono.just(true) : resets.open(user.id(), tokenHash, now, expiresAt))
                .doOnNext(issued -> {
                    metrics.counter(REQUEST_METER, "outcome", issued ? "issued" : "throttled").increment();
                    if (issued) {
                        mailer.dispatch(user.email(), properties.resetLink(token));
                    }
                }).then();
    }

    // Единственный победитель определяется удалением токена; проигравший откатывает пустую транзакцию.
    private Mono<Void> replacePassword(UUID userId, String tokenHash, String encoded) {
        return resets.consume(userId, tokenHash, clock.instant())
                .filter(consumed -> consumed)
                .switchIfEmpty(Mono.error(new StoreException(StoreError.INVALID_RECOVERY_TOKEN)))
                .then(Mono.defer(() -> users.replacePassword(userId, encoded)))
                .filter(rows -> rows == 1)
                .switchIfEmpty(Mono.error(new StoreException(StoreError.INVALID_RECOVERY_TOKEN)))
                .as(transactions::transactional).then();
    }

    private static String newToken() {
        byte[] bytes = new byte[AccountRules.RECOVERY_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // В базе лежит только SHA-256 токена: утечка таблицы не даёт рабочих ссылок.
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }
}
