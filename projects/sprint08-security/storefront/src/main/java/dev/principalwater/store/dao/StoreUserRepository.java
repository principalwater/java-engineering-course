package dev.principalwater.store.dao;

import dev.principalwater.store.model.StoreUser;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface StoreUserRepository extends ReactiveCrudRepository<StoreUser, UUID> {
    Mono<StoreUser> findByUsername(String username);
    Mono<StoreUser> findByCartId(UUID cartId);
    Mono<StoreUser> findByEmail(String email);
    @Query("SELECT auth_version FROM store_users WHERE id = :id")
    Mono<Long> findAuthVersion(UUID id);
    // Хеш и эпоха меняются одним UPDATE: старые сессии теряют силу в тот же commit, что и старый пароль.
    @Modifying
    @Query("UPDATE store_users SET password_hash = :passwordHash, auth_version = auth_version + 1, "
            + "version = version + 1 WHERE id = :id")
    Mono<Integer> replacePassword(UUID id, String passwordHash);
    // Оператор вносит контакт только там, где его ещё нет: чужую почту конфигурация не перезаписывает.
    @Modifying
    @Query("UPDATE store_users SET email = :email, version = version + 1 WHERE id = :id AND email IS NULL")
    Mono<Integer> attachEmail(UUID id, String email);
}
