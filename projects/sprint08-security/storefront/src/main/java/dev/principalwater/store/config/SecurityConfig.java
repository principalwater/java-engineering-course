package dev.principalwater.store.config;

import dev.principalwater.store.model.AccountRules;
import dev.principalwater.store.service.AccountService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;
import java.util.Set;
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
    private static final String LOGIN_PAGE = "/account/login";
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
        signedOut.setLogoutSuccessUrl(URI.create("/account/login?loggedOut"));
        var json = new MediaTypeServerWebExchangeMatcher(MediaType.APPLICATION_JSON);
        json.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        var entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegatingServerAuthenticationEntryPoint.DelegateEntry(json,
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
        entryPoint.setDefaultEntryPoint(new RedirectServerAuthenticationEntryPoint(LOGIN_PAGE));
        return http.authorizeExchange(access -> access
                        .pathMatchers("/account/seller", "/account/seller/**", "/catalog", "/catalog/**").hasRole("SELLER")
                        .pathMatchers(HttpMethod.GET, "/", "/items", "/items/*", LOGIN_PAGE, "/account/register",
                                "/store.css", "/accounts.css", "/store.js", "/images/**",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.POST, LOGIN_PAGE, "/account/register").permitAll()
                        .pathMatchers("/cart", "/cart/**", "/orders", "/orders/**", "/buy",
                                "/items", "/items/*", "/profile", "/profile/**", "/analytics",
                                "/account/logout", "/analytics.js", "/vendor/**").authenticated()
                        .anyExchange().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .securityContextRepository(context)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(login -> login.loginPage(LOGIN_PAGE).authenticationManager(authentication)
                        .securityContextRepository(context)
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler("/profile")))
                .logout(logout -> logout.logoutUrl("/account/logout")
                        .logoutHandler(new DelegatingServerLogoutHandler(clearContext, new WebSessionServerLogoutHandler(),
                                (web, principal) -> {
                                    // Удаляем также cookie гостевой корзины из предыдущей версии; она больше не даёт доступ.
                                    web.getExchange().getResponse().addCookie(ResponseCookie.from("storefront-cart", "")
                                            .path("/").httpOnly(true).sameSite("Lax").maxAge(0).build());
                                    return Mono.empty();
                                }))
                        .logoutSuccessHandler(signedOut))
                .csrf(config -> config.csrfTokenRequestHandler(csrf).accessDeniedHandler((exchange, failure) ->
                        Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied", failure))))
                .build();
    }
}
