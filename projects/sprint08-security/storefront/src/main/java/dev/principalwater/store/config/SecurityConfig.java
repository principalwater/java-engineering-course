package dev.principalwater.store.config;

import dev.principalwater.store.controller.StorePaths;
import dev.principalwater.store.service.AccountService;
import dev.principalwater.store.session.CredentialEpochRepository;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.DelegatingServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// Политика запросов и обработчики сессии. Проверка паролей находится в AccountAuthenticationConfig.
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SecurityConfig {
    private static final String SELLER_ROLE = "SELLER";
    private static final String[] SELLER_AREA = {StorePaths.STORE, StorePaths.STORE + "/**", StorePaths.CATALOG,
            StorePaths.CATALOG + "/**", "/catalog-demo.json"};
    private static final String[] PUBLIC_PAGES = {"/", StorePaths.ITEMS, StorePaths.ITEMS + "/*", StorePaths.LOGIN,
            StorePaths.REGISTER, StorePaths.RECOVERY, StorePaths.RECOVERY_RESET, "/store.css", "/accounts.css",
            "/store.js", "/images/**", "/actuator/health", "/actuator/health/**"};
    private static final String[] PUBLIC_FORMS = {StorePaths.LOGIN, StorePaths.REGISTER, StorePaths.RECOVERY,
            StorePaths.RECOVERY_RESET};
    private static final String[] SIGNED_IN_AREA = {"/cart", "/cart/**", "/orders", "/orders/**", "/buy",
            StorePaths.ITEMS, StorePaths.ITEMS + "/*", StorePaths.CABINET, StorePaths.CABINET + "/**",
            StorePaths.LOGOUT, "/analytics.js", "/vendor/**"};

    @Bean
    public SecurityWebFilterChain storeSecurity(ServerHttpSecurity http, ReactiveAuthenticationManager authentication,
                                                AccountService accounts) {
        // Сессия вне эпохи учётных данных не даёт входа: смена пароля отзывает все прежние сессии на всех репликах.
        ServerSecurityContextRepository context = new CredentialEpochRepository(
                new WebSessionServerSecurityContextRepository(), accounts::authVersion);
        var csrfTokens = new WebSessionServerCsrfTokenRepository();
        var requestCache = NoOpServerRequestCache.getInstance();
        return http.authorizeExchange(this::authorizeExchange)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(loginEntryPoint(requestCache)))
                .securityContextRepository(context)
                .requestCache(cache -> cache.requestCache(requestCache))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(login -> login.loginPage(StorePaths.LOGIN).authenticationManager(authentication)
                        .securityContextRepository(context)
                        .authenticationSuccessHandler(signedIn(csrfTokens, requestCache)))
                .logout(logout -> logout.logoutUrl(StorePaths.LOGOUT).logoutSuccessHandler(signedOut()))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(csrfRequestHandler())
                        .accessDeniedHandler(this::csrfDenied))
                .build();
    }

    // Побеждает первое совпавшее правило: область продавца идёт раньше общего кабинета, всё неназванное закрыто.
    private void authorizeExchange(ServerHttpSecurity.AuthorizeExchangeSpec access) {
        access.pathMatchers(SELLER_AREA).hasRole(SELLER_ROLE)
                .pathMatchers(HttpMethod.GET, PUBLIC_PAGES).permitAll()
                .pathMatchers(HttpMethod.POST, PUBLIC_FORMS).permitAll()
                .pathMatchers(SIGNED_IN_AREA).authenticated()
                .anyExchange().denyAll();
    }

    // Браузер получает редирект на вход, JSON-клиент — 401. У custom redirect handlers собственный request cache.
    private ServerAuthenticationEntryPoint loginEntryPoint(NoOpServerRequestCache requestCache) {
        var json = new MediaTypeServerWebExchangeMatcher(MediaType.APPLICATION_JSON);
        json.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        var entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegatingServerAuthenticationEntryPoint.DelegateEntry(json,
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
        var loginEntry = new RedirectServerAuthenticationEntryPoint(StorePaths.LOGIN);
        loginEntry.setRequestCache(requestCache);
        entryPoint.setDefaultEntryPoint(loginEntry);
        return entryPoint;
    }

    // Вход меняет id сессии, но WebFlux оставляет гостевой CSRF token: заменяем его в самом ответе входа,
    // чтобы следующая страница не генерировала токен и не меняла id сессии повторно.
    private ServerAuthenticationSuccessHandler signedIn(ServerCsrfTokenRepository csrfTokens, NoOpServerRequestCache requestCache) {
        var toCabinet = new RedirectServerAuthenticationSuccessHandler(StorePaths.CABINET);
        toCabinet.setRequestCache(requestCache);
        return new DelegatingServerAuthenticationSuccessHandler((web, principal) -> csrfTokens
                .generateToken(web.getExchange()).flatMap(token -> csrfTokens.saveToken(web.getExchange(), token)), toCabinet);
    }

    // Spring Security очищает context и CSRF, меняя id сессии. Закрываем её последним шагом,
    // иначе поздняя запись CSRF может восстановить часть уже удалённой сессии Redis.
    private ServerLogoutSuccessHandler signedOut() {
        var redirect = new RedirectServerLogoutSuccessHandler();
        redirect.setLogoutSuccessUrl(URI.create(StorePaths.LOGIN + "?loggedOut"));
        var closeSession = new WebSessionServerLogoutHandler();
        return (exchange, authentication) -> closeSession.logout(exchange, authentication)
                .then(Mono.defer(() -> redirect.onLogoutSuccess(exchange, authentication)));
    }

    // Импорт через обычную multipart-форму сохраняет CSRF и без JavaScript; лимиты частей заданы в Boot.
    private XorServerCsrfTokenRequestAttributeHandler csrfRequestHandler() {
        var handler = new XorServerCsrfTokenRequestAttributeHandler();
        handler.setTokenFromMultipartDataEnabled(true);
        return handler;
    }

    private Mono<Void> csrfDenied(ServerWebExchange exchange, AccessDeniedException failure) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied", failure));
    }
}
