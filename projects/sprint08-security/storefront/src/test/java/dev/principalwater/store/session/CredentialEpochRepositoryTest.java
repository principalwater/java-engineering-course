package dev.principalwater.store.session;

import dev.principalwater.store.ReactiveTestBase;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.StoreUserDetails;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CredentialEpochRepositoryTest extends ReactiveTestBase {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final long SESSION_EPOCH = 3;
    private final AtomicInteger lookups = new AtomicInteger();

    @Test
    void onlyTheCurrentEpochKeepsTheSessionAuthenticated() {
        var signedIn = context(account());
        assertSame(signedIn, load(signedIn, id -> Mono.just(SESSION_EPOCH)).block(TIMEOUT));
        // После смены пароля номер эпохи в базе вырос, а удалённый аккаунт вообще не имеет эпохи.
        StepVerifier.create(load(signedIn, id -> Mono.just(SESSION_EPOCH + 1))).verifyComplete();
        StepVerifier.create(load(signedIn, id -> Mono.empty())).verifyComplete();
        // Чужой authenticated principal из хранилища не даёт входа, даже если его тип допускает JSON Security.
        var anonymous = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("guest", null, List.of()));
        StepVerifier.create(load(anonymous, id -> {
            lookups.incrementAndGet();
            return Mono.just(0L);
        })).verifyComplete();
        assertEquals(0, lookups.get());
    }

    @Test
    void oneRequestAsksTheDatabaseOnceAndASavedContextIsCheckedAgain() {
        var signedIn = context(account());
        var repository = new CredentialEpochRepository(fixed(signedIn), id -> {
            lookups.incrementAndGet();
            return Mono.just(SESSION_EPOCH);
        });
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/cabinet"));
        for (int index = 0; index < 3; index++) {
            assertSame(signedIn, repository.load(exchange).block(TIMEOUT));
        }
        assertEquals(1, lookups.get(), "ReactorContextWebFilter subscribes to load once per context access");
        repository.save(exchange, signedIn).block(TIMEOUT);
        repository.load(exchange).block(TIMEOUT);
        assertEquals(2, lookups.get(), "A context saved during the request must be verified afresh");
    }

    @Test
    void aDatabaseFailureFailsTheRequestInsteadOfTrustingTheSession() {
        var failure = new DataAccessResourceFailureException("database is down");
        StepVerifier.create(load(context(account()), id -> Mono.error(failure))).expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
    }

    private static Mono<SecurityContext> load(SecurityContext stored, Function<UUID, Mono<Long>> epoch) {
        return new CredentialEpochRepository(fixed(stored), epoch)
                .load(MockServerWebExchange.from(MockServerHttpRequest.get("/cabinet")));
    }

    private static StoreUserDetails account() {
        return new StoreUserDetails(ACCOUNT, UUID.randomUUID(), "epoch.user", "Epoch", AccountRole.BUYER, SESSION_EPOCH);
    }

    private static SecurityContext context(StoreUserDetails principal) {
        return new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    // Хранилище сессии заменено фиксированным ответом: проверяется только решение об эпохе.
    private static ServerSecurityContextRepository fixed(SecurityContext stored) {
        return new ServerSecurityContextRepository() {
            @Override
            public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
                return Mono.empty();
            }

            @Override
            public Mono<SecurityContext> load(ServerWebExchange exchange) {
                return Mono.just(stored);
            }
        };
    }
}
