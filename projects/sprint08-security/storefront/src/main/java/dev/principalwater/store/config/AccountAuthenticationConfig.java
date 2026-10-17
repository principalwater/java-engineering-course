package dev.principalwater.store.config;

import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.service.AccountService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

// Кто и как проверяется: хеширование паролей и менеджер аутентификации. Правила доступа к адресам — в SecurityConfig.
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AccountAuthenticationConfig {
    private static final int PASSWORD_WORKERS = 2;
    private static final int PASSWORD_QUEUE_CAPACITY = 64;

    @Bean(destroyMethod = "dispose")
    public Scheduler passwordScheduler() {
        return Schedulers.newBoundedElastic(PASSWORD_WORKERS, PASSWORD_QUEUE_CAPACITY, "store-password");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveAuthenticationManager accountAuthentication(AccountService accounts, PasswordEncoder passwords,
                                                               Scheduler passwordScheduler) {
        var manager = new UserDetailsRepositoryReactiveAuthenticationManager(accounts);
        manager.setPasswordEncoder(passwords);
        manager.setScheduler(passwordScheduler);
        return authentication -> {
            String password = authentication.getCredentials() instanceof String value ? value : null;
            if (!AccountRules.validCredentials(authentication.getName(), password)) {
                return Mono.error(new BadCredentialsException("Invalid username or password"));
            }
            return manager.authenticate(authentication).doOnNext(result -> {
                // Сессии нужен владелец корзины, а не сырой пароль или его хеш.
                if (result instanceof CredentialsContainer credentials) {
                    credentials.eraseCredentials();
                }
            });
        };
    }
}
