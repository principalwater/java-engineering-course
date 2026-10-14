package dev.principalwater.store.controller;

import dev.principalwater.store.DatabaseTestBase;
import dev.principalwater.store.config.CartProperties;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.service.CatalogImportService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AccountIntegrationTest extends DatabaseTestBase {
    private static final String PASSWORD = "correct horse account pass";
    private static final Pattern CSRF = Pattern.compile(
            "<input\\b(?=[^>]*\\bname=\"_csrf\")[^>]*\\bvalue=\"([^\"]+)\"");
    @Autowired private CatalogImportService importer;
    @Autowired private StoreUserRepository users;
    @Autowired private CartRepository carts;
    @Autowired private CartProperties cartProperties;
    private WebTestClient web;
    private String sessionCookie;

    @BeforeEach
    void client() {
        // Полный Boot-контекст исполняет настоящие security filters, form login, cookie и R2DBC без mock principal.
        web = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(TIMEOUT).build();
        sessionCookie = context.getEnvironment().getProperty("server.reactive.session.cookie.name", "SESSION");
    }

    @Test
    void accountCartAndOrdersFollowLoginAcrossBrowsersAndLogoutRestoresTheGuestCart() {
        await(importer.importJson("""
                [{"sku":"account-item","title":"Account item","description":"Description","image":"CUP","price":1250}]
                """.getBytes(StandardCharsets.UTF_8)));
        long productId = await(database.sql("SELECT id FROM products WHERE sku = 'account-item'")
                .map(row -> row.get("id", Long.class)).one());
        var first = new Browser();
        first.get("/account/register", HttpStatus.OK);
        String guestCookie = first.cookies.get(cartProperties.cookieName());
        first.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "1"),
                true, HttpStatus.SEE_OTHER);
        register(first, "Alice.Shop", "Алиса", HttpStatus.SEE_OTHER);
        StoreUser account = await(users.findByUsername("alice.shop"));
        assertNotEquals(guestCookie, account.cartId().toString());
        assertNotEquals(PASSWORD, account.passwordHash());

        first.get("/account/login", HttpStatus.OK);
        String anonymousSession = first.cookies.get(sessionCookie);
        login(first, "ALICE.SHOP", PASSWORD);
        assertNotEquals(anonymousSession, first.cookies.get(sessionCookie), "Login must rotate the session ID");
        assertEquals(guestCookie, first.cookies.get(cartProperties.cookieName()));
        assertTrue(first.get("/profile", HttpStatus.OK).contains("Алиса"));
        first.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "2"),
                true, HttpStatus.SEE_OTHER);

        var second = new Browser();
        second.get("/account/login", HttpStatus.OK);
        login(second, "alice.shop", PASSWORD);
        assertTrue(second.get("/cart/items", HttpStatus.OK).contains("data-cart-total=\"2500\""));
        second.get("/profile", HttpStatus.OK);
        var purchased = second.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);
        String orderPath = Objects.requireNonNull(purchased.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .split("\\?", 2)[0];
        first.get(orderPath, HttpStatus.OK);
        String profile = first.get("/profile", HttpStatus.OK);
        assertTrue(profile.contains("data-metric=\"orders\">1</strong>"));
        assertTrue(profile.contains("data-metric=\"units\">2</strong>"));
        assertTrue(first.get("/orders", HttpStatus.OK).contains(orderPath));

        var forged = new Browser();
        forged.cookies.put(cartProperties.cookieName(), account.cartId().toString());
        forged.get(orderPath, HttpStatus.NOT_FOUND);
        assertNotEquals(account.cartId().toString(), forged.cookies.get(cartProperties.cookieName()));
        var otherAccount = new Browser();
        otherAccount.get("/account/register", HttpStatus.OK);
        register(otherAccount, "bob.shop", "Борис", HttpStatus.SEE_OTHER);
        otherAccount.get("/account/login", HttpStatus.OK);
        login(otherAccount, "bob.shop", PASSWORD);
        otherAccount.cookies.put(cartProperties.cookieName(), account.cartId().toString());
        otherAccount.get(orderPath, HttpStatus.NOT_FOUND);
        assertFalse(otherAccount.get("/orders", HttpStatus.OK).contains(orderPath));

        first.get("/profile", HttpStatus.OK);
        first.post("/account/logout", Map.of(), false, HttpStatus.FORBIDDEN);
        first.get(orderPath, HttpStatus.OK);
        first.get("/profile", HttpStatus.OK);
        String loggedInSession = first.cookies.get(sessionCookie);
        first.post("/account/logout", Map.of(), true, HttpStatus.FOUND);
        assertEquals(guestCookie, first.cookies.get(cartProperties.cookieName()));
        assertTrue(first.get("/cart/items", HttpStatus.OK).contains("data-cart-total=\"1250\""));
        first.get(orderPath, HttpStatus.NOT_FOUND);
        var expired = new Browser();
        expired.cookies.put(sessionCookie, loggedInSession);
        expired.cookies.put(cartProperties.cookieName(), guestCookie);
        assertTrue(expired.get("/profile", HttpStatus.OK).contains("Сейчас вы гость"));
        second.get(orderPath, HttpStatus.OK);
    }

    @Test
    void registrationAndLoginValidateCredentialsAndCsrfWithoutCreatingOrExposingAnAccount() {
        var browser = new Browser();
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", "alice.shop", "displayName", "Алиса", "password", PASSWORD),
                false, HttpStatus.FORBIDDEN);
        assertEquals(0L, await(users.count()));
        browser.get("/account/register", HttpStatus.OK);
        register(browser, "alice.shop", "Алиса", HttpStatus.SEE_OTHER);
        browser.get("/account/register", HttpStatus.OK);
        long cartCount = await(carts.count());
        register(browser, "ALICE.SHOP", "Другая Алиса", HttpStatus.CONFLICT);
        assertEquals(1L, await(users.count()));
        assertEquals(cartCount, await(carts.count()), "Duplicate registration must roll back its new cart");
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", "unicode.shop", "displayName", "Гость", "password", "я".repeat(40)),
                true, HttpStatus.BAD_REQUEST);
        assertEquals(1L, await(users.count()));
        assertEquals(cartCount, await(carts.count()));

        String loginPage = browser.get("/account/login", HttpStatus.OK);
        assertFalse(loginPage.contains("Не удалось войти."));
        assertFalse(loginPage.contains("Аккаунт создан."));
        browser.post("/account/login", Map.of("username", "alice.shop", "password", PASSWORD),
                false, HttpStatus.FORBIDDEN);
        assertTrue(browser.get("/profile", HttpStatus.OK).contains("Сейчас вы гость"));
        browser.get("/account/login", HttpStatus.OK);
        var failure = browser.post("/account/login", Map.of("username", "alice.shop", "password", "a wrong password"),
                true, HttpStatus.FOUND);
        assertEquals("/account/login?error", failure.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        assertTrue(browser.get("/account/login?error", HttpStatus.OK).contains("Не удалось войти."));
        assertFalse(browser.get("/account/login?error", HttpStatus.OK).contains("Аккаунт создан."));
        assertTrue(browser.get("/account/login?registered", HttpStatus.OK).contains("Аккаунт создан."));
        assertFalse(browser.get("/account/login?registered", HttpStatus.OK).contains("Не удалось войти."));
        assertTrue(browser.get("/profile", HttpStatus.OK).contains("Сейчас вы гость"));
        browser.get("/account/login", HttpStatus.OK);
        login(browser, "ALICE.SHOP", PASSWORD);
        assertTrue(browser.get("/profile", HttpStatus.OK).contains("Алиса"));
    }

    private void register(Browser browser, String username, String name, HttpStatus status) {
        browser.post("/account/register", Map.of("username", username, "displayName", name, "password", PASSWORD), true, status);
    }

    private void login(Browser browser, String username, String password) {
        var result = browser.post("/account/login", Map.of("username", username, "password", password), true, HttpStatus.FOUND);
        assertEquals("/profile", result.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
    }

    private final class Browser {
        private final Map<String, String> cookies = new HashMap<>();
        private String csrf;

        private String get(String path, HttpStatus status) {
            var result = web.get().uri(path).accept(MediaType.TEXT_HTML)
                    .cookies(values -> cookies.forEach(values::set)).exchange().expectStatus().isEqualTo(status)
                    .expectBody(String.class).returnResult();
            saveCookies(result);
            String html = Objects.requireNonNull(result.getResponseBody());
            var token = CSRF.matcher(html);
            if (token.find()) {
                csrf = token.group(1);
            }
            return html;
        }

        private EntityExchangeResult<String> post(String path, Map<String, String> values, boolean withCsrf, HttpStatus status) {
            var form = new LinkedMultiValueMap<String, String>();
            values.forEach(form::set);
            if (withCsrf) {
                assertNotNull(csrf, "A rendered form must supply the CSRF token");
                form.set("_csrf", csrf);
            }
            var result = web.post().uri(path).accept(MediaType.TEXT_HTML).cookies(request -> cookies.forEach(request::set))
                    .body(BodyInserters.fromFormData(form)).exchange().expectStatus().isEqualTo(status)
                    .expectBody(String.class).returnResult();
            saveCookies(result);
            return result;
        }

        private void saveCookies(EntityExchangeResult<?> result) {
            result.getResponseCookies().forEach((name, values) -> values.forEach(cookie -> {
                assertTrue(cookie.isHttpOnly(), "Identity cookies must be HttpOnly");
                if (cookie.getMaxAge().isZero()) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, cookie.getValue());
                }
            }));
        }
    }
}
