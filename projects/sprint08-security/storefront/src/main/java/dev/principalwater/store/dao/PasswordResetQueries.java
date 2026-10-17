package dev.principalwater.store.dao;

import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class PasswordResetQueries {
    private static final String USER_ID = "userId";
    private static final String TOKEN_HASH = "tokenHash";
    private static final String REQUESTED_AT = "requestedAt";
    private static final String EXPIRES_AT = "expiresAt";
    private final DatabaseClient database;

    public PasswordResetQueries(DatabaseClient database) {
        this.database = database;
    }

    // Пауза между выпусками проверяется самим условным UPDATE: две реплики не выпустят токен одновременно.
    public Mono<Boolean> reissue(UUID userId, String tokenHash, Instant requestedAt, Instant expiresAt, Instant cooldownBefore) {
        return database.sql("""
                UPDATE password_resets SET token_hash = :tokenHash, requested_at = :requestedAt, expires_at = :expiresAt
                WHERE user_id = :userId AND requested_at <= :cooldownBefore
                """).bind(TOKEN_HASH, tokenHash).bind(REQUESTED_AT, requestedAt).bind(EXPIRES_AT, expiresAt)
                .bind(USER_ID, userId).bind("cooldownBefore", cooldownBefore).fetch().rowsUpdated().map(rows -> rows == 1);
    }

    // PRIMARY KEY(user_id): дубликат означает, что строка уже есть и пауза ещё не прошла, а не ошибку.
    public Mono<Boolean> open(UUID userId, String tokenHash, Instant requestedAt, Instant expiresAt) {
        return database.sql("""
                INSERT INTO password_resets(user_id, token_hash, requested_at, expires_at)
                VALUES (:userId, :tokenHash, :requestedAt, :expiresAt)
                """).bind(USER_ID, userId).bind(TOKEN_HASH, tokenHash).bind(REQUESTED_AT, requestedAt)
                .bind(EXPIRES_AT, expiresAt).fetch().rowsUpdated().map(rows -> rows == 1)
                .onErrorResume(DuplicateKeyException.class, failure -> Mono.just(false));
    }

    public Mono<UUID> findOwner(String tokenHash, Instant now) {
        return database.sql("SELECT user_id FROM password_resets WHERE token_hash = :tokenHash AND expires_at > :now")
                .bind(TOKEN_HASH, tokenHash).bind("now", now).map(row -> row.get("user_id", UUID.class)).one();
    }

    // Единственный потребитель токена определяется числом удалённых строк, а не предварительным чтением.
    public Mono<Boolean> consume(UUID userId, String tokenHash, Instant now) {
        return database.sql("DELETE FROM password_resets WHERE user_id = :userId AND token_hash = :tokenHash AND expires_at > :now")
                .bind(USER_ID, userId).bind(TOKEN_HASH, tokenHash).bind("now", now).fetch().rowsUpdated()
                .map(rows -> rows == 1);
    }
}
