package dev.principalwater.store.config;

import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.service.AccountService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SecurityConfig {
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

    @Bean
    public SecurityWebFilterChain storeSecurity(ServerHttpSecurity http, ReactiveAuthenticationManager authentication) {
        var csrf = new XorServerCsrfTokenRequestAttributeHandler();
        // Импорт через обычную multipart-форму сохраняет CSRF и без JavaScript; лимиты частей заданы в Boot.
        csrf.setTokenFromMultipartDataEnabled(true);
        var context = new WebSessionServerSecurityContextRepository();
        var clearContext = new SecurityContextServerLogoutHandler();
        clearContext.setSecurityContextRepository(context);
        var signedOut = new RedirectServerLogoutSuccessHandler();
        signedOut.setLogoutSuccessUrl(URI.create("/profile?loggedOut"));
        return http.authorizeExchange(access -> access.anyExchange().permitAll())
                .securityContextRepository(context)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(login -> login.loginPage("/account/login").authenticationManager(authentication)
                        .securityContextRepository(context)
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler("/profile")))
                .logout(logout -> logout.logoutUrl("/account/logout")
                        .logoutHandler(new DelegatingServerLogoutHandler(clearContext, new WebSessionServerLogoutHandler()))
                        .logoutSuccessHandler(signedOut))
                .csrf(config -> config.csrfTokenRequestHandler(csrf).accessDeniedHandler((exchange, failure) ->
                        Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied", failure))))
                .build();
    }
}
