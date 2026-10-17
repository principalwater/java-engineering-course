package dev.principalwater.store.session;

import dev.principalwater.store.model.StoreUserDetails;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Хранилище контекста, которое отвергает вход прежней эпохи учётных данных.
 *
 * <p>Сессия в Redis переживает смену пароля и видна всем репликам, поэтому право определяется не ею, а базой:
 * сохранённая эпоха аккаунта сверяется с текущей на каждом запросе. Несовпадение даёт анонимный запрос, и обычные
 * правила Spring Security отправляют его на вход. Проверка не записывает в сессию: устаревший вход просто не действует.
 */
public final class CredentialEpochRepository implements ServerSecurityContextRepository {
    private static final String CHECKED_ATTRIBUTE = CredentialEpochRepository.class.getName() + ".CHECKED";
    private final ServerSecurityContextRepository delegate;
    private final Function<UUID, Mono<Long>> currentEpoch;

    public CredentialEpochRepository(ServerSecurityContextRepository delegate, Function<UUID, Mono<Long>> currentEpoch) {
        this.delegate = delegate;
        this.currentEpoch = currentEpoch;
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        exchange.getAttributes().remove(CHECKED_ATTRIBUTE);
        return delegate.save(exchange, context);
    }

    // ReactorContextWebFilter подписывается на load при каждом обращении к контексту: запрос в базу делается один раз.
    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        return ((Checked) exchange.getAttributes().computeIfAbsent(CHECKED_ATTRIBUTE,
                key -> new Checked(delegate.load(exchange).flatMap(this::verify).cache()))).context();
    }

    private Mono<SecurityContext> verify(SecurityContext context) {
        if (context.getAuthentication() == null || !(context.getAuthentication().getPrincipal() instanceof StoreUserDetails account)) {
            return Mono.empty();
        }
        // Ошибка базы не превращается в «действителен»: запрос завершается ошибкой, а не пропускается.
        return currentEpoch.apply(account.accountId()).filter(epoch -> epoch == account.authVersion()).map(epoch -> context);
    }

    private record Checked(Mono<SecurityContext> context) {
    }
}
