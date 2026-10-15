package dev.principalwater.store.config;

import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.model.ShoppingCart;
import dev.principalwater.store.model.StoreUser;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Component
@EnableConfigurationProperties(SellerProperties.class)
public class SellerBootstrap implements ApplicationRunner {
    private final SellerProperties properties;
    private final StoreUserRepository users;
    private final CartRepository carts;
    private final PasswordEncoder passwords;
    private final Scheduler passwordScheduler;
    private final TransactionalOperator transactions;
    public SellerBootstrap(SellerProperties properties, StoreUserRepository users, CartRepository carts,
                           PasswordEncoder passwords, Scheduler passwordScheduler, TransactionalOperator transactions) {
        this.properties = properties;
        this.users = users;
        this.carts = carts;
        this.passwords = passwords;
        this.passwordScheduler = passwordScheduler;
        this.transactions = transactions;
    }
    @Override
    public void run(ApplicationArguments arguments) {
        if (properties.bootstrapPassword().isEmpty()) { return; }
        String username = AccountRules.normalizeUsername(properties.username());
        // Единственный startup wait выполняется на main-потоке до готовности приложения, не на event loop.
        users.findByUsername(username).flatMap(existing -> existing.role() == AccountRole.SELLER
                ? Mono.just(existing) : Mono.error(new IllegalStateException("Seller username belongs to a buyer")))
                .switchIfEmpty(Mono.defer(() -> Mono.fromCallable(() -> passwords.encode(properties.bootstrapPassword()))
                        .subscribeOn(passwordScheduler).flatMap(hash -> carts.save(new ShoppingCart(UUID.randomUUID()))
                                .flatMap(cart -> users.save(new StoreUser(UUID.randomUUID(), cart.id(), username,
                                        hash, "Магазин", AccountRole.SELLER, null))).as(transactions::transactional))))
                .block(Duration.ofSeconds(15));
    }
}
