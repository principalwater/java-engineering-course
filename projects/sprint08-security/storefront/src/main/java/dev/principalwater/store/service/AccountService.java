package dev.principalwater.store.service;

import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.AccountRegistration;
import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.model.ShoppingCart;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.model.StoreUserDetails;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Service
public class AccountService implements ReactiveUserDetailsService {
    private final StoreUserRepository users;
    private final CartRepository carts;
    private final PasswordEncoder passwords;
    private final Scheduler passwordScheduler;
    private final TransactionalOperator transactions;

    public AccountService(StoreUserRepository users, CartRepository carts, PasswordEncoder passwords,
                          Scheduler passwordScheduler, TransactionalOperator transactions) {
        this.users = users;
        this.carts = carts;
        this.passwords = passwords;
        this.passwordScheduler = passwordScheduler;
        this.transactions = transactions;
    }

    public Mono<StoreUser> register(AccountRegistration registration) {
        return Mono.defer(() -> {
            if (!AccountRules.validCredentials(registration.username(), registration.password())
                    || registration.displayName() == null || registration.displayName().isBlank()
                    || registration.displayName().length() > AccountRules.DISPLAY_NAME_MAX_LENGTH) {
                return Mono.error(new StoreException(StoreError.INVALID_ACCOUNT));
            }
            String username = AccountRules.normalizeUsername(registration.username());
            // Хеширование не занимает event loop или connection; лимит очереди принадлежит общему scheduler.
            return Mono.fromCallable(() -> passwords.encode(registration.password())).subscribeOn(passwordScheduler)
                    .flatMap(hash -> carts.save(new ShoppingCart(UUID.randomUUID())).flatMap(cart ->
                            users.save(new StoreUser(UUID.randomUUID(), cart.id(), username, hash,
                                    registration.displayName().strip()))).as(transactions::transactional))
                    .onErrorMap(DuplicateKeyException.class, failure -> new StoreException(StoreError.ACCOUNT_EXISTS));
        });
    }

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        if (username == null || username.length() > AccountRules.USERNAME_MAX_LENGTH
                || !username.matches(AccountRules.USERNAME_PATTERN)) {
            return Mono.empty();
        }
        return users.findByUsername(AccountRules.normalizeUsername(username)).map(StoreUserDetails::new);
    }
}
