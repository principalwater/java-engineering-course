package dev.principalwater.bank.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class JwtFixture implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public JwtFixture() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            key =
                    new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                            .privateKey((RSAPrivateKey) pair.getPrivate())
                            .keyID(UUID.randomUUID().toString())
                            .build();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext(
                    "/jwks",
                    exchange -> {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, jwks.length);
                        try (var response = exchange.getResponseBody()) {
                            response.write(jwks);
                        }
                    });
            server.start();
        } catch (IOException | java.security.GeneralSecurityException exception) {
            executor.shutdownNow();
            throw new IllegalStateException("Cannot start JWT fixture", exception);
        }
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String jwks() {
        return issuer() + "/jwks";
    }

    public String user(String subject, String login, String scopes) {
        return token(
                subject,
                "bank-front",
                List.of("bank-api"),
                scopes,
                issuer(),
                Instant.now().plusSeconds(300),
                login);
    }

    public String analyst(String subject, String login, String scopes) {
        return token(
                subject,
                "bank-front",
                List.of("bank-api"),
                scopes,
                issuer(),
                Instant.now().plusSeconds(300),
                login,
                List.of("analyst"));
    }

    public String service(String caller, String audience, String scopes) {
        return token(
                "service-account-" + caller,
                caller,
                List.of(audience),
                scopes,
                issuer(),
                Instant.now().plusSeconds(300),
                "service-account-" + caller);
    }

    public String token(
            String subject,
            String caller,
            List<String> audience,
            String scopes,
            String issuer,
            Instant expiresAt,
            String login) {
        return token(subject, caller, audience, scopes, issuer, expiresAt, login, List.of());
    }

    private String token(
            String subject,
            String caller,
            List<String> audience,
            String scopes,
            String issuer,
            Instant expiresAt,
            String login,
            List<String> roles) {
        JWTClaimsSet.Builder builder =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(30)))
                        .expirationTime(Date.from(expiresAt))
                        .claim("azp", caller)
                        .claim("scope", scopes)
                        .claim("preferred_username", login);
        if (!roles.isEmpty()) {
            builder.claim("realm_access", Map.of("roles", roles));
        }
        JWTClaimsSet claims = builder.build();
        SignedJWT token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims);
        try {
            token.sign(new RSASSASigner(key));
            return token.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot sign fixture token", exception);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
