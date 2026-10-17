package dev.principalwater.payments;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Настоящий OAuth-провайдер для тестов: RSA-ключи, JWK Set по HTTP и выпуск подписанных access token.
 * Сервер живёт всё время JVM, потому что закэшированный Spring-контекст хранит его адрес и декодер
 * кэширует ключи: перезапуск на другом порту сломал бы повторно используемый контекст.
 */
public final class PaymentTokens {
    public static final String AUDIENCE = "payments";
    public static final String CLIENT = "storefront";
    public static final String READ = "payments.read";
    public static final String WRITE = "payments.write";
    private static final String REALM_PATH = "/realms/storefront-api";
    private static final String CERTS_PATH = REALM_PATH + "/protocol/openid-connect/certs";
    private static final String KEY_ID = "payments-signing-key";
    private static final int RSA_KEY_BITS = 2048;
    private static final int HMAC_SECRET_BYTES = 32;
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final RSAKey SIGNING_KEY = generate(KEY_ID);
    // Тот же kid, другой ключевой материал: сервис находит ключ по kid, но подпись обязана не сойтись.
    private static final RSAKey FORGED_KEY = generate(KEY_ID);
    private static final RSAKey ROTATED_KEY = generate("payments-rotated-key");
    private static final AtomicReference<String> JWK_SET = new AtomicReference<>(publish(SIGNING_KEY));
    private static final AtomicBoolean KEY_ENDPOINT_STALLED = new AtomicBoolean();
    private static final DisposableServer SERVER = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.get(CERTS_PATH, (request, response) -> response
                    .header(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .sendString(Mono.defer(() -> KEY_ENDPOINT_STALLED.get() ? Mono.never() : Mono.just(JWK_SET.get())))))
            .bindNow(Duration.ofSeconds(5));
    public static final String ISSUER = "http://127.0.0.1:" + SERVER.port() + REALM_PATH;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SERVER::disposeNow));
    }

    private PaymentTokens() {
    }

    /** jwk-set-uri намеренно не задаётся: проверяется вывод адреса ключей из issuer по умолчанию. */
    public static void registerIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    }

    public static JWTClaimsSet.Builder claims(String... scopes) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(UUID.randomUUID().toString()).audience(AUDIENCE)
                .claim("azp", CLIENT).claim("scope", String.join(" ", scopes)).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(VALIDITY)));
    }

    public static String token(String... scopes) {
        return sign(claims(scopes));
    }

    public static String sign(JWTClaimsSet.Builder claims) {
        return sign(SIGNING_KEY, claims);
    }

    public static String signWithForgedKey(JWTClaimsSet.Builder claims) {
        return sign(FORGED_KEY, claims);
    }

    /** Ключ, который провайдер ещё не опубликовал: до {@link #publishRotatedKey()} его kid сервису неизвестен. */
    public static String signWithRotatedKey(JWTClaimsSet.Builder claims) {
        return sign(ROTATED_KEY, claims);
    }

    /** Токен с kid, которого нет в кэше декодера: проверка подписи обязана обратиться к JWK Set. */
    public static String signWithUnknownKey(JWTClaimsSet.Builder claims) {
        return sign(generate("payments-unknown-" + UUID.randomUUID()), claims);
    }

    /** На время действия провайдер принимает соединение с JWK Set, но не отвечает. */
    public static void whileKeyEndpointStalls(Runnable action) {
        KEY_ENDPOINT_STALLED.set(true);
        try {
            action.run();
        } finally {
            KEY_ENDPOINT_STALLED.set(false);
        }
    }

    public static void publishRotatedKey() {
        JWK_SET.set(publish(SIGNING_KEY, ROTATED_KEY));
    }

    public static String unsigned(JWTClaimsSet.Builder claims) {
        return new PlainJWT(claims.build()).serialize();
    }

    public static String signWithSharedSecret(JWTClaimsSet.Builder claims) {
        byte[] secret = new byte[HMAC_SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        try {
            return serialize(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims, new MACSigner(secret));
        } catch (JOSEException failure) {
            throw new IllegalStateException("Cannot create the HMAC test signer", failure);
        }
    }

    private static String sign(RSAKey key, JWTClaimsSet.Builder claims) {
        try {
            var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build();
            return serialize(header, claims, new RSASSASigner(key));
        } catch (JOSEException failure) {
            throw new IllegalStateException("Cannot create the RSA test signer", failure);
        }
    }

    private static String serialize(JWSHeader header, JWTClaimsSet.Builder claims, JWSSigner signer) throws JOSEException {
        var jwt = new SignedJWT(header, claims.build());
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(RSA_KEY_BITS).keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException failure) {
            throw new IllegalStateException("Cannot generate the RSA test key", failure);
        }
    }

    private static String publish(RSAKey... keys) {
        return new JWKSet(List.<JWK>of(keys)).toString(true);
    }
}
