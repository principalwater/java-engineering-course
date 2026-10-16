package dev.principalwater.store.controller;

import dev.principalwater.store.Browser;
import dev.principalwater.store.PaymentsStub;
import dev.principalwater.store.StoreIntegrationTestBase;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.service.CatalogImportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountIntegrationTest extends StoreIntegrationTestBase {
    private static final String PASSWORD = "correct horse account pass";
    @Autowired private CatalogImportService importer;
    @Autowired private StoreUserRepository users;
    @Autowired private CartRepository carts;
    private WebTestClient web;
    private String sessionCookie;

    @BeforeEach
    void client() {
        // Полный Boot-контекст исполняет настоящие security filters, form login, cookie и R2DBC без mock principal.
        web = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(TIMEOUT).build();
        sessionCookie = context.getEnvironment().getProperty("server.reactive.session.cookie.name", "SESSION");
    }

    @Test
    void accountCartAndOrdersFollowLoginAcrossBrowsersAndLogoutInvalidatesTheWholeSession() {
        await(importer.importJson("""
                [{"sku":"account-item","title":"Account item","description":"Description","image":"CUP","price":1250}]
                """.getBytes(StandardCharsets.UTF_8)));
        long productId = await(database.sql("SELECT id FROM products WHERE sku = 'account-item'")
                .map(row -> row.get("id", Long.class)).one());
        var first = new Browser(web);
        first.get("/account/register", HttpStatus.OK);
        assertFalse(first.cookies.containsKey("storefront-cart"));
        assertEquals(0L, await(carts.count()), "Anonymous pages must not allocate a cart");
        register(first, "Alice.Shop", "Алиса", HttpStatus.SEE_OTHER);
        StoreUser account = await(users.findByUsername("alice.shop"));
        assertNotEquals(PASSWORD, account.passwordHash());

        first.get("/account/login", HttpStatus.OK);
        String anonymousSession = first.cookies.get(sessionCookie);
        login(first, "ALICE.SHOP", PASSWORD);
        assertNotEquals(anonymousSession, first.cookies.get(sessionCookie), "Login must rotate the session ID");
        assertFalse(first.cookies.containsKey("storefront-cart"));
        assertTrue(first.get("/cabinet", HttpStatus.OK).contains("Алиса"));
        first.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "2"),
                true, HttpStatus.SEE_OTHER);

        var second = new Browser(web);
        second.get("/account/login", HttpStatus.OK);
        login(second, "alice.shop", PASSWORD);
        assertTrue(second.get("/cart/items", HttpStatus.OK).contains("data-cart-total=\"2500\""));
        second.get("/cabinet", HttpStatus.OK);
        var purchased = second.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);
        String orderPath = Objects.requireNonNull(purchased.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .split("\\?", 2)[0];
        first.get(orderPath, HttpStatus.OK);
        String profile = first.get("/cabinet", HttpStatus.OK);
        assertTrue(profile.contains("data-metric=\"orders\">1</strong>"));
        assertTrue(profile.contains("data-metric=\"units\">2</strong>"));
        assertTrue(first.get("/orders", HttpStatus.OK).contains(orderPath));

        var forged = new Browser(web);
        forged.cookies.put("storefront-cart", account.cartId().toString());
        forged.get(orderPath, HttpStatus.FOUND);
        var otherAccount = new Browser(web);
        otherAccount.get(orderPath, HttpStatus.FOUND);
        otherAccount.get("/account/register", HttpStatus.OK);
        register(otherAccount, "bob.shop", "Борис", HttpStatus.SEE_OTHER);
        otherAccount.get("/account/login", HttpStatus.OK);
        login(otherAccount, "bob.shop", PASSWORD);
        otherAccount.cookies.put("storefront-cart", account.cartId().toString());
        otherAccount.get(orderPath, HttpStatus.NOT_FOUND);
        assertFalse(otherAccount.get("/orders", HttpStatus.OK).contains(orderPath));
        web.get().uri(orderPath + "/payment").accept(MediaType.APPLICATION_JSON)
                .cookies(values -> otherAccount.cookies.forEach(values::set)).exchange().expectStatus().isNotFound();
        assertTrue(otherAccount.get("/cart/items", HttpStatus.OK).contains("data-cart-total=\"0\""));
        assertNotEquals(account.cartId(), await(users.findByUsername("bob.shop")).cartId());
        assertEquals(1, PaymentsStub.DEBITS.get());
        otherAccount.post("/cart/items", Map.of("id", Long.toString(productId), "action", "PLUS"), true, HttpStatus.SEE_OTHER);
        var secondPurchase = otherAccount.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);
        String secondOrderPath = Objects.requireNonNull(secondPurchase.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .split("\\?", 2)[0];
        first.get(secondOrderPath, HttpStatus.NOT_FOUND);
        otherAccount.get(secondOrderPath, HttpStatus.OK);
        StoreUser bob = await(users.findByUsername("bob.shop"));
        assertEquals(1_000_000_000L - 250_000, PaymentsStub.balance(account.id()));
        assertEquals(1_000_000_000L - 125_000, PaymentsStub.balance(bob.id()));
        assertEquals(2, PaymentsStub.DEBITS.get());
        web.get().uri(secondOrderPath + "/payment").accept(MediaType.APPLICATION_JSON)
                .cookies(values -> otherAccount.cookies.forEach(values::set)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.accountId").isEqualTo(bob.id().toString());

        first.get("/cabinet", HttpStatus.OK);
        first.post("/account/logout", Map.of(), false, HttpStatus.FORBIDDEN);
        first.get(orderPath, HttpStatus.OK);
        first.get("/cabinet", HttpStatus.OK);
        String loggedInSession = first.cookies.get(sessionCookie);
        assertTrue(first.get("/account/logout", HttpStatus.OK).contains("Выйти из аккаунта?"));
        first.get(orderPath, HttpStatus.OK);
        first.post("/account/logout", Map.of(), true, HttpStatus.FOUND);
        assertFalse(first.cookies.containsKey("storefront-cart"));
        assertFalse(first.cookies.containsKey(sessionCookie));
        first.get("/cart/items", HttpStatus.FOUND);
        first.get(orderPath, HttpStatus.FOUND);
        var expired = new Browser(web);
        expired.cookies.put(sessionCookie, loggedInSession);
        expired.get("/cabinet", HttpStatus.FOUND);
        expired.get(orderPath, HttpStatus.FOUND);
        assertTrue(first.get("/account/login?loggedOut", HttpStatus.OK).contains("Сессия этого браузера завершена"));
        second.get(orderPath, HttpStatus.OK);
    }

    @Test
    void regularProfilesKeepTheLocalWebSessionBecauseSharedSessionsAreOptIn() {
        // Boot привязал бы автонастроенные сессии к кэшевому Redis, где записи вытесняются; профиль shared-sessions явный.
        assertTrue(context.getBeansOfType(ReactiveSessionRepository.class).isEmpty());
    }

    @Test
    void loginReplacesTheCsrfTokenOfTheAnonymousSession() {
        var browser = new Browser(web);
        browser.get("/account/register", HttpStatus.OK);
        register(browser, "token.shop", "Токен", HttpStatus.SEE_OTHER);
        browser.get("/account/login", HttpStatus.OK);
        String anonymousToken = browser.csrf;
        login(browser, "token.shop", PASSWORD);

        // Токен со страницы входа выдан гостевой сессии: после входа он отклоняется, новый берётся со страницы с формой.
        browser.csrf = anonymousToken;
        browser.post("/account/logout", Map.of(), true, HttpStatus.FORBIDDEN);
        browser.csrf = null;
        browser.post("/account/logout", Map.of(), true, HttpStatus.FOUND);
    }

    @Test
    void anonymousVisitorsSeeOnlyPublicProductsAndCannotReadOrChangePrivateData() {
        var guest = new Browser(web);
        String catalog = guest.get("/items", HttpStatus.OK);
        assertFalse(catalog.contains("class=\"quantity-form\""));
        assertFalse(catalog.contains("href=\"/orders\""));
        assertFalse(catalog.contains("href=\"/cart/items\""));
        guest.get("/account/login", HttpStatus.OK);
        for (String path : List.of("/cabinet", "/cart/items", "/orders", "/orders/1", "/cabinet/analytics/data")) {
            guest.get(path, HttpStatus.FOUND);
            web.get().uri(path).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isUnauthorized();
        }
        for (String path : List.of("/items", "/items/1", "/cart/items", "/buy")) {
            // Валидный CSRF из публичной формы: отказ обязан исходить от authentication, а не другого guard.
            guest.post(path, Map.of("id", "1", "action", "PLUS", "checkoutKey", UUID.randomUUID().toString(),
                    "amountMinor", "100"), true, HttpStatus.FOUND);
        }
        assertEquals(0L, await(carts.count()));
        assertEquals(0L, await(users.count()));
        assertEquals(0, PaymentsStub.DEBITS.get());
    }

    @Test
    void registrationAndLoginValidateCredentialsAndCsrfWithoutCreatingOrExposingAnAccount() {
        var browser = new Browser(web);
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", "alice.shop", "displayName", "Алиса",
                "email", "alice.shop@example.test", "password", PASSWORD), false, HttpStatus.FORBIDDEN);
        assertEquals(0L, await(users.count()));
        browser.get("/account/register", HttpStatus.OK);
        register(browser, "alice.shop", "Алиса", HttpStatus.SEE_OTHER);
        browser.get("/account/register", HttpStatus.OK);
        long cartCount = await(carts.count());
        register(browser, "ALICE.SHOP", "Другая Алиса", HttpStatus.CONFLICT);
        assertEquals(1L, await(users.count()));
        assertEquals(cartCount, await(carts.count()), "Duplicate registration must roll back its new cart");
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", "unicode.shop", "displayName", "Гость",
                "email", "unicode.shop@example.test", "password", "я".repeat(40)), true, HttpStatus.BAD_REQUEST);
        assertEquals(1L, await(users.count()));
        assertEquals(cartCount, await(carts.count()));

        // Контактная почта обязательна и уникальна без учёта регистра; отказ не оставляет ни аккаунта, ни корзины.
        browser.get("/account/register", HttpStatus.OK);
        browser.post("/account/register", Map.of("username", "mail.none", "displayName", "Гость", "password", PASSWORD),
                true, HttpStatus.BAD_REQUEST);
        for (String invalid : List.of("not-an-address", "two@@example.test", "user@localhost", "a".repeat(250) + "@example.test")) {
            browser.post("/account/register", Map.of("username", "mail.bad", "displayName", "Гость",
                    "email", invalid, "password", PASSWORD), true, HttpStatus.BAD_REQUEST);
        }
        browser.post("/account/register", Map.of("username", "mail.twin", "displayName", "Двойник",
                "email", "ALICE.Shop@Example.TEST", "password", PASSWORD), true, HttpStatus.CONFLICT);
        assertEquals(1L, await(users.count()));
        assertEquals(cartCount, await(carts.count()));
        assertEquals("alice.shop@example.test", await(users.findByUsername("alice.shop")).email());

        String loginPage = browser.get("/account/login", HttpStatus.OK);
        assertFalse(loginPage.contains("Не удалось войти."));
        assertFalse(loginPage.contains("Аккаунт создан."));
        browser.post("/account/login", Map.of("username", "alice.shop", "password", PASSWORD),
                false, HttpStatus.FORBIDDEN);
        browser.get("/cabinet", HttpStatus.FOUND);
        browser.get("/account/login", HttpStatus.OK);
        var failure = browser.post("/account/login", Map.of("username", "alice.shop", "password", "a wrong password"),
                true, HttpStatus.FOUND);
        assertEquals("/account/login?error", failure.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        assertTrue(browser.get("/account/login?error", HttpStatus.OK).contains("Не удалось войти."));
        assertFalse(browser.get("/account/login?error", HttpStatus.OK).contains("Аккаунт создан."));
        assertTrue(browser.get("/account/login?registered", HttpStatus.OK).contains("Аккаунт создан."));
        assertFalse(browser.get("/account/login?registered", HttpStatus.OK).contains("Не удалось войти."));
        browser.get("/cabinet", HttpStatus.FOUND);
        browser.get("/account/login", HttpStatus.OK);
        login(browser, "ALICE.SHOP", PASSWORD);
        assertTrue(browser.get("/cabinet", HttpStatus.OK).contains("Алиса"));
    }

    @Test
    void onlyPersistedSellersManageTheStoreAndBuyerSpendingRemainsSeparateFromRevenue() {
        String catalog = """
                [{"sku":"seller-item","title":"Seller item","description":"Description","image":"CUP","price":1250}]
                """;
        var guest = new Browser(web);
        guest.get("/account/register", HttpStatus.OK);
        for (String path : List.of("/cabinet/store", "/cabinet/store/analytics", "/cabinet/store/analytics/data", "/catalog")) {
            guest.get(path, HttpStatus.FOUND);
        }
        guest.upload(catalog, HttpStatus.FOUND);
        guest.post("/cabinet/store/analytics/sync", Map.of("days", "7"), true, HttpStatus.FOUND);

        var buyer = new Browser(web);
        buyer.get("/account/register", HttpStatus.OK);
        buyer.post("/account/register", Map.of("username", "buyer.shop", "displayName", "Покупатель",
                "email", "buyer.shop@example.test", "password", PASSWORD, "role", "SELLER"), true, HttpStatus.SEE_OTHER);
        assertEquals(AccountRole.BUYER, await(users.findByUsername("buyer.shop")).role(),
                "Public registration must ignore a requested privileged role");
        buyer.get("/account/login", HttpStatus.OK);
        login(buyer, "buyer.shop", PASSWORD);
        buyer.get("/cabinet", HttpStatus.OK);
        for (String path : List.of("/cabinet/store", "/cabinet/store/analytics", "/cabinet/store/analytics/data", "/catalog")) {
            buyer.get(path, HttpStatus.FORBIDDEN);
        }
        buyer.upload(catalog, HttpStatus.FORBIDDEN);
        buyer.post("/cabinet/store/analytics/sync", Map.of("days", "7"), true, HttpStatus.FORBIDDEN);
        assertEquals(0L, await(database.sql("SELECT COUNT(*) AS total FROM products")
                .map(row -> ((Number) row.get("total")).longValue()).one()));

        var seller = new Browser(web);
        seller.get("/account/register", HttpStatus.OK);
        register(seller, "owner.shop", "Продавец", HttpStatus.SEE_OTHER);
        // Назначение продавца находится вне публичной регистрации; вход читает реальную сохранённую роль.
        execute("UPDATE store_users SET role = 'SELLER' WHERE username = 'owner.shop'");
        seller.get("/account/login", HttpStatus.OK);
        login(seller, "owner.shop", PASSWORD);
        seller.get("/catalog", HttpStatus.OK);
        seller.upload(catalog, HttpStatus.SEE_OTHER);
        long productId = await(database.sql("SELECT id FROM products WHERE sku = 'seller-item'")
                .map(row -> row.get("id", Long.class)).one());
        buyer.get("/cabinet", HttpStatus.OK);
        buyer.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "2"),
                true, HttpStatus.SEE_OTHER);
        buyer.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);
        seller.get("/cabinet", HttpStatus.OK);
        seller.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "1"),
                true, HttpStatus.SEE_OTHER);
        seller.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);

        String buyerProfile = buyer.get("/cabinet", HttpStatus.OK);
        String sellerProfile = seller.get("/cabinet", HttpStatus.OK);
        String store = seller.get("/cabinet/store", HttpStatus.OK);
        assertEquals("2500", metric(buyerProfile, "paid-value"));
        assertEquals("1250", metric(sellerProfile, "paid-value"));
        assertEquals("3750", metric(store, "seller-revenue"));
        assertEquals("2", metric(store, "seller-orders"));
        assertEquals("3", metric(store, "seller-units"));
        assertFalse(buyerProfile.contains("Выручка"));
        assertFalse(sellerProfile.contains("Выручка"));
        assertTrue(buyer.get("/cabinet/analytics", HttpStatus.OK).contains("Только ваши покупки"));
        assertTrue(seller.get("/cabinet/store/analytics", HttpStatus.OK).contains("Весь магазин"));
        // Отключённый warehouse отвечает 503 после разрешённого доступа, а не подменяет результаты нулями.
        seller.post("/cabinet/store/analytics/sync", Map.of("days", "7"), true, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void cartExplainsBalanceFailuresAndRecoversOnePendingPaymentThroughTheSamePost() {
        await(importer.importJson("""
                [{"sku":"wallet-item","title":"Wallet item","description":"Description","image":"CUP","price":1250}]
                """.getBytes(StandardCharsets.UTF_8)));
        long productId = await(database.sql("SELECT id FROM products WHERE sku = 'wallet-item'")
                .map(row -> row.get("id", Long.class)).one());
        var buyer = new Browser(web);
        buyer.get("/account/register", HttpStatus.OK);
        register(buyer, "wallet.shop", "Покупатель", HttpStatus.SEE_OTHER);
        StoreUser account = await(users.findByUsername("wallet.shop"));
        buyer.get("/account/login", HttpStatus.OK);
        login(buyer, "wallet.shop", PASSWORD);
        buyer.get("/cabinet", HttpStatus.OK);
        buyer.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "1"),
                true, HttpStatus.SEE_OTHER);

        PaymentsStub.funds(account.id(), 0);
        String insufficient = buyer.get("/cart/items", HttpStatus.OK);
        assertTrue(disabledCheckout(insufficient));
        assertTrue(insufficient.contains("Недостаточно средств"));
        PaymentsStub.UNAVAILABLE.set(true);
        String unavailable = buyer.get("/cart/items", HttpStatus.OK);
        assertTrue(disabledCheckout(unavailable));
        assertTrue(unavailable.contains("Сервис платежей временно недоступен"));
        assertTrue(buyer.get("/cabinet", HttpStatus.OK).contains("Не удалось проверить баланс"));
        assertEquals(0L, await(database.sql("SELECT COUNT(*) AS total FROM purchase_orders")
                .map(row -> ((Number) row.get("total")).longValue()).one()));

        PaymentsStub.UNAVAILABLE.set(false);
        PaymentsStub.funds(account.id(), 125_000);
        assertFalse(disabledCheckout(buyer.get("/cart/items", HttpStatus.OK)));
        PaymentsStub.LOSE_RESPONSE.set(true);
        String uncertain = buyer.post("/buy", Map.of(), true, HttpStatus.SERVICE_UNAVAILABLE).getResponseBody();
        assertNotNull(uncertain);
        assertTrue(uncertain.contains("Деньги могли быть списаны"));
        assertTrue(uncertain.contains("Проверить платёж"));
        String pending = buyer.get("/cart/items", HttpStatus.OK);
        assertFalse(disabledCheckout(pending));
        assertTrue(checkoutForm(pending).contains("Проверить платёж"));
        assertTrue(pending.contains("Уточняем результат платежа"));
        assertTrue(Pattern.compile("<ul\\b[^>]*\\sinert(?:\\s|=|>)[^>]*>").matcher(pending).find());
        buyer.post("/cart/items", Map.of("id", Long.toString(productId), "action", "PLUS"),
                true, HttpStatus.CONFLICT);
        var completed = buyer.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);
        String orderPath = Objects.requireNonNull(completed.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        assertTrue(buyer.get(orderPath, HttpStatus.OK).contains("Оплата подтверждена"));
        assertEquals("1250", metric(buyer.get("/cabinet", HttpStatus.OK), "paid-value"));
        assertEquals(1, PaymentsStub.DEBITS.get());
    }

    private void register(Browser browser, String username, String name, HttpStatus status) {
        browser.post("/account/register", Map.of("username", username, "displayName", name,
                "email", username.toLowerCase(Locale.ROOT) + "@example.test", "password", PASSWORD), true, status);
    }

    private void login(Browser browser, String username, String password) {
        var result = browser.post("/account/login", Map.of("username", username, "password", password), true, HttpStatus.FOUND);
        assertEquals("/cabinet", result.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        // Вход заменил токен гостевой сессии, а профиль форм не содержит: новый токен берём со страницы с формой.
        browser.csrf = null;
    }

    private static String metric(String html, String key) {
        var value = Pattern.compile("<[^>]+\\bdata-metric=\"" + Pattern.quote(key) + "\"[^>]*>([^<]+)</").matcher(html);
        assertTrue(value.find(), "Expected rendered metric " + key);
        return value.group(1).replaceAll("[^0-9]", "");
    }

    private static boolean disabledCheckout(String html) {
        return Pattern.compile("<button\\b[^>]*\\sdisabled(?:\\s|=|>)[^>]*>")
                .matcher(checkoutForm(html)).find();
    }

    private static String checkoutForm(String html) {
        var form = Pattern.compile("<form\\b[^>]*\\bdata-checkout\\b[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
        assertTrue(form.find(), "The cart must contain its checkout form");
        return form.group(1);
    }
}
