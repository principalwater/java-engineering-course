package dev.principalwater.store.controller;

import dev.principalwater.store.PaymentsStub;
import dev.principalwater.store.StoreIntegrationTestBase;
import dev.principalwater.store.dao.CartRepository;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.service.CatalogImportService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountIntegrationTest extends StoreIntegrationTestBase {
    private static final String PASSWORD = "correct horse account pass";
    private static final Pattern CSRF = Pattern.compile(
            "<input\\b(?=[^>]*\\bname=\"_csrf\")[^>]*\\bvalue=\"([^\"]+)\"");
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
        var first = new Browser();
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
        forged.cookies.put("storefront-cart", account.cartId().toString());
        forged.get(orderPath, HttpStatus.FOUND);
        var otherAccount = new Browser();
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

        first.get("/profile", HttpStatus.OK);
        first.post("/account/logout", Map.of(), false, HttpStatus.FORBIDDEN);
        first.get(orderPath, HttpStatus.OK);
        first.get("/profile", HttpStatus.OK);
        String loggedInSession = first.cookies.get(sessionCookie);
        assertTrue(first.get("/account/logout", HttpStatus.OK).contains("Выйти из аккаунта?"));
        first.get(orderPath, HttpStatus.OK);
        first.post("/account/logout", Map.of(), true, HttpStatus.FOUND);
        assertFalse(first.cookies.containsKey("storefront-cart"));
        assertFalse(first.cookies.containsKey(sessionCookie));
        first.get("/cart/items", HttpStatus.FOUND);
        first.get(orderPath, HttpStatus.FOUND);
        var expired = new Browser();
        expired.cookies.put(sessionCookie, loggedInSession);
        expired.get("/profile", HttpStatus.FOUND);
        expired.get(orderPath, HttpStatus.FOUND);
        assertTrue(first.get("/account/login?loggedOut", HttpStatus.OK).contains("Сессия этого браузера завершена"));
        second.get(orderPath, HttpStatus.OK);
    }

    @Test
    void anonymousVisitorsSeeOnlyPublicProductsAndCannotReadOrChangePrivateData() {
        var guest = new Browser();
        String catalog = guest.get("/items", HttpStatus.OK);
        assertFalse(catalog.contains("class=\"quantity-form\""));
        assertFalse(catalog.contains("href=\"/orders\""));
        assertFalse(catalog.contains("href=\"/cart/items\""));
        guest.get("/account/login", HttpStatus.OK);
        for (String path : List.of("/profile", "/cart/items", "/orders", "/orders/1", "/profile/analytics/data")) {
            guest.get(path, HttpStatus.FOUND);
            web.get().uri(path).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isUnauthorized();
        }
        for (String path : List.of("/items", "/items/1", "/cart/items", "/buy")) {
            // Валидный CSRF из публичной формы: отказ обязан исходить от authentication, а не другого guard.
            guest.post(path, Map.of("id", "1", "action", "PLUS", "checkoutKey", java.util.UUID.randomUUID().toString(),
                    "amountMinor", "100"), true, HttpStatus.FOUND);
        }
        assertEquals(0L, await(carts.count()));
        assertEquals(0L, await(users.count()));
        assertEquals(0, PaymentsStub.DEBITS.get());
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
        browser.get("/profile", HttpStatus.FOUND);
        browser.get("/account/login", HttpStatus.OK);
        var failure = browser.post("/account/login", Map.of("username", "alice.shop", "password", "a wrong password"),
                true, HttpStatus.FOUND);
        assertEquals("/account/login?error", failure.getResponseHeaders().getFirst(HttpHeaders.LOCATION));
        assertTrue(browser.get("/account/login?error", HttpStatus.OK).contains("Не удалось войти."));
        assertFalse(browser.get("/account/login?error", HttpStatus.OK).contains("Аккаунт создан."));
        assertTrue(browser.get("/account/login?registered", HttpStatus.OK).contains("Аккаунт создан."));
        assertFalse(browser.get("/account/login?registered", HttpStatus.OK).contains("Не удалось войти."));
        browser.get("/profile", HttpStatus.FOUND);
        browser.get("/account/login", HttpStatus.OK);
        login(browser, "ALICE.SHOP", PASSWORD);
        assertTrue(browser.get("/profile", HttpStatus.OK).contains("Алиса"));
    }

    @Test
    void onlyPersistedSellersManageTheStoreAndBuyerSpendingRemainsSeparateFromRevenue() {
        String catalog = """
                [{"sku":"seller-item","title":"Seller item","description":"Description","image":"CUP","price":1250}]
                """;
        var guest = new Browser();
        guest.get("/account/register", HttpStatus.OK);
        for (String path : List.of("/account/seller", "/account/seller/analytics", "/account/seller/analytics/data", "/catalog")) {
            guest.get(path, HttpStatus.FOUND);
        }
        guest.upload(catalog, HttpStatus.FOUND);
        guest.post("/account/seller/analytics/sync", Map.of("days", "7"), true, HttpStatus.FOUND);

        var buyer = new Browser();
        buyer.get("/account/register", HttpStatus.OK);
        buyer.post("/account/register", Map.of("username", "buyer.shop", "displayName", "Покупатель",
                "password", PASSWORD, "role", "SELLER"), true, HttpStatus.SEE_OTHER);
        assertEquals(AccountRole.BUYER, await(users.findByUsername("buyer.shop")).role(),
                "Public registration must ignore a requested privileged role");
        buyer.get("/account/login", HttpStatus.OK);
        login(buyer, "buyer.shop", PASSWORD);
        buyer.get("/profile", HttpStatus.OK);
        for (String path : List.of("/account/seller", "/account/seller/analytics", "/account/seller/analytics/data", "/catalog")) {
            buyer.get(path, HttpStatus.FORBIDDEN);
        }
        buyer.upload(catalog, HttpStatus.FORBIDDEN);
        buyer.post("/account/seller/analytics/sync", Map.of("days", "7"), true, HttpStatus.FORBIDDEN);
        assertEquals(0L, await(database.sql("SELECT COUNT(*) AS total FROM products")
                .map(row -> ((Number) row.get("total")).longValue()).one()));

        var seller = new Browser();
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
        buyer.get("/profile", HttpStatus.OK);
        buyer.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "2"),
                true, HttpStatus.SEE_OTHER);
        buyer.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);
        seller.get("/profile", HttpStatus.OK);
        seller.post("/cart/items", Map.of("id", Long.toString(productId), "action", "SET", "quantity", "1"),
                true, HttpStatus.SEE_OTHER);
        seller.post("/buy", Map.of(), true, HttpStatus.SEE_OTHER);

        String buyerProfile = buyer.get("/profile", HttpStatus.OK);
        String sellerProfile = seller.get("/profile", HttpStatus.OK);
        String store = seller.get("/account/seller", HttpStatus.OK);
        assertEquals("2500", metric(buyerProfile, "paid-value"));
        assertEquals("1250", metric(sellerProfile, "paid-value"));
        assertEquals("3750", metric(store, "seller-revenue"));
        assertEquals("2", metric(store, "seller-orders"));
        assertEquals("3", metric(store, "seller-units"));
        assertFalse(buyerProfile.contains("Выручка"));
        assertFalse(sellerProfile.contains("Выручка"));
        assertTrue(buyer.get("/profile/analytics", HttpStatus.OK).contains("Только ваши покупки"));
        assertTrue(seller.get("/account/seller/analytics", HttpStatus.OK).contains("Весь магазин"));
        // Отключённый warehouse отвечает 503 после разрешённого доступа, а не подменяет результаты нулями.
        seller.post("/account/seller/analytics/sync", Map.of("days", "7"), true, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void cartExplainsBalanceFailuresAndRecoversOnePendingPaymentThroughTheSamePost() {
        await(importer.importJson("""
                [{"sku":"wallet-item","title":"Wallet item","description":"Description","image":"CUP","price":1250}]
                """.getBytes(StandardCharsets.UTF_8)));
        long productId = await(database.sql("SELECT id FROM products WHERE sku = 'wallet-item'")
                .map(row -> row.get("id", Long.class)).one());
        var buyer = new Browser();
        buyer.get("/account/register", HttpStatus.OK);
        register(buyer, "wallet.shop", "Покупатель", HttpStatus.SEE_OTHER);
        StoreUser account = await(users.findByUsername("wallet.shop"));
        buyer.get("/account/login", HttpStatus.OK);
        login(buyer, "wallet.shop", PASSWORD);
        buyer.get("/profile", HttpStatus.OK);
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
        assertTrue(buyer.get("/profile", HttpStatus.OK).contains("Не удалось проверить баланс"));
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
        assertEquals("1250", metric(buyer.get("/profile", HttpStatus.OK), "paid-value"));
        assertEquals(1, PaymentsStub.DEBITS.get());
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
        private String checkoutKey;
        private String amountMinor;

        private String get(String path, HttpStatus status) {
            var result = web.get().uri(path).accept(MediaType.TEXT_HTML)
                    .cookies(values -> cookies.forEach(values::set)).exchange().expectStatus().isEqualTo(status)
                    .expectBody(String.class).returnResult();
            saveCookies(result);
            String html = Objects.requireNonNullElse(result.getResponseBody(), "");
            var token = CSRF.matcher(html);
            if (token.find()) {
                csrf = token.group(1);
            }
            var key = Pattern.compile("<input\\b(?=[^>]*\\bname=\"checkoutKey\")[^>]*\\bvalue=\"([^\"]+)\"").matcher(html);
            if (key.find()) {
                checkoutKey = key.group(1);
            }
            var amount = Pattern.compile("<input\\b(?=[^>]*\\bname=\"amountMinor\")[^>]*\\bvalue=\"([^\"]+)\"").matcher(html);
            if (amount.find()) {
                amountMinor = amount.group(1);
            }
            return html;
        }

        private EntityExchangeResult<String> post(String path, Map<String, String> values, boolean withCsrf, HttpStatus status) {
            if (path.equals("/buy") && !values.containsKey("checkoutKey") && (checkoutKey == null || amountMinor == null)) {
                get("/cart/items", HttpStatus.OK);
            }
            var form = new LinkedMultiValueMap<String, String>();
            values.forEach(form::set);
            if (path.equals("/buy") && !values.containsKey("checkoutKey")) {
                assertNotNull(checkoutKey, "The rendered checkout must supply its stable payment key");
                form.set("checkoutKey", checkoutKey);
            }
            if (path.equals("/buy") && !values.containsKey("amountMinor")) {
                assertNotNull(amountMinor, "The rendered checkout must supply its visible amount");
                form.set("amountMinor", amountMinor);
            }
            if (withCsrf) {
                assertNotNull(csrf, "A rendered form must supply the CSRF token");
                form.set("_csrf", csrf);
            }
            var result = web.post().uri(path).accept(MediaType.TEXT_HTML).cookies(request -> cookies.forEach(request::set))
                    .body(BodyInserters.fromFormData(form)).exchange().expectStatus().isEqualTo(status)
                    .expectBody(String.class).returnResult();
            saveCookies(result);
            if (status.is2xxSuccessful() || status.is3xxRedirection()) {
                if (path.equals("/cart/items") || path.equals("/account/login") || path.equals("/account/logout")) {
                    checkoutKey = null;
                    amountMinor = null;
                }
            }
            return result;
        }

        private void upload(String catalog, HttpStatus status) {
            assertNotNull(csrf, "A rendered form must supply the CSRF token");
            var form = new MultipartBodyBuilder();
            form.part("catalog", catalog.getBytes(StandardCharsets.UTF_8)).filename("catalog.json")
                    .contentType(MediaType.APPLICATION_JSON);
            form.part("_csrf", csrf);
            var result = web.post().uri("/catalog/import").accept(MediaType.TEXT_HTML)
                    .cookies(request -> cookies.forEach(request::set)).body(BodyInserters.fromMultipartData(form.build()))
                    .exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult();
            saveCookies(result);
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
