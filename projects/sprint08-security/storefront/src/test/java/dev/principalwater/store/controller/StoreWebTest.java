package dev.principalwater.store.controller;

import dev.principalwater.store.TestBase;
import dev.principalwater.store.config.AccountAuthenticationConfig;
import dev.principalwater.store.config.SecurityConfig;
import dev.principalwater.store.config.WebConfig;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.CartChange;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogPage;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ImportResult;
import dev.principalwater.store.model.ItemView;
import dev.principalwater.store.model.OrderHistoryPage;
import dev.principalwater.store.model.OrderLineView;
import dev.principalwater.store.model.OrderStatus;
import dev.principalwater.store.model.OrderView;
import dev.principalwater.store.model.Paging;
import dev.principalwater.store.model.ProfileView;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.model.StoreUserDetails;
import dev.principalwater.store.payment.CheckoutAvailability;
import dev.principalwater.store.payment.WalletBalanceView;
import dev.principalwater.store.payment.WalletService;
import dev.principalwater.store.runtime.ResourceAdmissionFilter;
import dev.principalwater.store.service.AccountService;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogImportService;
import dev.principalwater.store.service.CatalogService;
import dev.principalwater.store.service.CheckoutService;
import dev.principalwater.store.service.OrderService;
import dev.principalwater.store.service.RecoveryService;
import dev.principalwater.store.util.DisplayFormats;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

@WebFluxTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ResourceAdmissionFilter.class),
        controllers = {CatalogController.class, CartController.class, OrderController.class,
        CatalogImportController.class, ProfileController.class, RecoveryController.class})
@ActiveProfiles("test")
@Import({AccountAuthenticationConfig.class, SecurityConfig.class, WebConfig.class, DisplayFormats.class})
class StoreWebTest extends TestBase {
    @MockitoBean private AccountService accounts;
    private static final UUID CART_ID = UUID.fromString("652b77b8-9b3f-498c-9b99-321bd2fd3949");
    private static final UUID CHECKOUT_KEY = UUID.fromString("644ddf44-8edf-3cf9-9af1-43d26e0bf5b5");
    private static final CartView EMPTY_CART = new CartView(List.of());
    @Autowired private WebTestClient anonymous;
    private WebTestClient web;
    @MockitoBean private CartService carts;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private CheckoutService checkout;
    @MockitoBean private OrderService orders;
    @MockitoBean private CatalogImportService importer;
    @MockitoBean private WalletService wallets;
    @MockitoBean private RecoveryService recovery;

    @BeforeEach
    void cartOwner() {
        web = client(AccountRole.BUYER);
        when(carts.view(any())).thenReturn(Mono.just(EMPTY_CART));
        when(carts.change(any(), anyLong(), any(), any())).thenReturn(Mono.just(EMPTY_CART));
        when(carts.changeWithReceipt(any(), anyLong(), any(), any())).thenReturn(Mono.just(new CartChange(EMPTY_CART, CHECKOUT_KEY)));
        when(wallets.balance(any())).thenReturn(Mono.just(WalletBalanceView.available(1_000_000)));
        when(checkout.availability(any())).thenReturn(Mono.just(new CheckoutAvailability(
                CheckoutAvailability.Status.AVAILABLE, "1000000", "500000", "0", CHECKOUT_KEY.toString())));
    }

    @Test
    void feedUsesDefaultParametersAndEscapesProductText() {
        var item = new ItemView(7, "test", "<script>alert(1)</script>", "<img src=x onerror=alert(2)>",
                "/images/cup.svg", 2500, 0);
        when(catalog.list(any(), any())).thenReturn(Mono.just(new CatalogPage(List.of(item), new Paging(5, 1, false, false), 1)));

        web.get().uri("/").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectCookie().doesNotExist("storefront-cart").expectBody(String.class).value(html -> {
                    assertTrue(html.contains("&lt;script&gt;"));
                    assertTrue(html.contains("&lt;img"));
                    assertFalse(html.contains("<script>alert(1)</script>"));
                    assertFalse(html.contains("<img src=x onerror=alert(2)>"));
                });
        var query = ArgumentCaptor.forClass(CatalogQuery.class);
        verify(catalog).list(query.capture(), any());
        assertEquals(new CatalogQuery("", SortMode.NO, 1, 5), query.getValue());
    }

    @Test
    void itemCartOrderAndProfilePagesRenderTheirModels() {
        var item = new ItemView(7, "test", "Keyboard", "Description", "/images/keyboard.svg", 2500, 2);
        when(catalog.item(anyLong(), any())).thenReturn(Mono.just(item));
        when(carts.view(any())).thenReturn(Mono.just(new CartView(List.of(item))));
        var line = new OrderLineView(7, "test", "Keyboard", "/images/keyboard.svg", 2500, 2);
        var purchasedAt = Instant.parse("2026-10-12T10:00:00Z");
        var order = new OrderView(42, purchasedAt, List.of(line), 5000, OrderStatus.PAID, UUID.randomUUID());
        when(orders.history(CART_ID, 20, null)).thenReturn(Mono.just(new OrderHistoryPage(List.of(order), null)));
        when(orders.get(42, CART_ID)).thenReturn(Mono.just(order));
        when(orders.profile(CART_ID)).thenReturn(Mono.just(new ProfileView(1, 5000, 2, purchasedAt, 5000, 1, 0)));

        web.get().uri("/items/7").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("Keyboard")));
        web.get().uri("/cart/items").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("data-cart-total=\"5000\""));
                    assertTrue(Pattern.compile("<input\\b(?=[^>]*\\bname=\"checkoutKey\")[^>]*\\bvalue=\""
                            + CHECKOUT_KEY + "\"").matcher(html).find());
                });
        web.get().uri("/orders").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("/orders/42")));
        web.get().uri("/orders/42?newOrder=true").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("Заказ оформлен"));
                    assertTrue(html.contains("12.10.2026, 13:00"));
                    assertTrue(html.contains("data-order-total=\"5000\""));
                });
        web.get().uri("/cabinet").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("12.10.2026, 13:00"));
                    assertTrue(html.contains("/cabinet/analytics"));
                    assertTrue(html.contains("Потрачено"));
                    assertFalse(html.contains("Выручка"));
                });
    }

    @Test
    void orderHistoryOffersTheWayBackToNewestOrdersOnlyAfterACursor() {
        when(orders.history(CART_ID, 20, null)).thenReturn(Mono.just(new OrderHistoryPage(List.of(), null)));
        when(orders.history(CART_ID, 20, "next-page")).thenReturn(Mono.just(new OrderHistoryPage(List.of(), null)));

        web.get().uri("/orders").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertFalse(html.contains("К новым заказам"));
                    assertFalse(html.contains("Более ранние заказы"));
                });
        web.get().uri("/orders?cursor=next-page").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("К новым заказам"));
                    assertFalse(html.contains("Более ранние заказы"));
                });
    }

    @Test
    void cartMutationPreservesLiteralSearchCharactersInTheRedirect() {
        web.mutateWith(csrf()).post().uri("/items").body(BodyInserters.fromFormData("id", "7")
                        .with("action", "PLUS").with("search", "C++ & blue").with("sort", "ALPHA")
                        .with("pageNumber", "2").with("pageSize", "5"))
                .exchange().expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION,
                        "/items?search=C%2B%2B%20%26%20blue&sort=ALPHA&pageNumber=2&pageSize=5");
    }

    @Test
    void cartMutationReturnsItsServerCalculatedQuantityAndTotalsForJson() {
        var item = new ItemView(7, "test", "Keyboard", "Description", "/images/keyboard.svg", 2500, 3);
        when(carts.changeWithReceipt(CART_ID, 7, CartAction.SET, 3))
                .thenReturn(Mono.just(new CartChange(new CartView(List.of(item)), CHECKOUT_KEY)));
        web.mutateWith(csrf()).post().uri("/cart/items").accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("id", "7").with("action", "SET").with("quantity", "3"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.itemId").isEqualTo(7).jsonPath("$.count").isEqualTo(3)
                .jsonPath("$.cart.total").isEqualTo(7500).jsonPath("$.cart.itemCount").isEqualTo(3)
                .jsonPath("$.checkoutKey").isEqualTo(CHECKOUT_KEY.toString());
    }

    @Test
    void checkoutNeedsCsrfAndRedirectsToTheNewOrder() {
        when(checkout.buy(CART_ID, CHECKOUT_KEY, 500_000)).thenReturn(Mono.just(42L));
        web.post().uri("/buy").accept(MediaType.TEXT_HTML).exchange().expectStatus().isForbidden();
        web.mutateWith(csrf()).post().uri("/buy").exchange().expectStatus().isBadRequest();
        web.mutateWith(csrf()).post().uri("/buy")
                .body(BodyInserters.fromFormData("checkoutKey", CHECKOUT_KEY.toString()).with("amountMinor", "500000"))
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/orders/42?newOrder=true");
    }

    @Test
    void invalidParametersAndForeignOrdersReturnSafeErrors() {
        web.get().uri("/items?sort=CUSTOM").accept(MediaType.TEXT_HTML).exchange().expectStatus().isBadRequest();
        web.get().uri("/items?pageSize=3").accept(MediaType.TEXT_HTML).exchange().expectStatus().isBadRequest();
        web.get().uri("/items/-1").accept(MediaType.TEXT_HTML).exchange().expectStatus().isBadRequest();
        when(orders.get(99, CART_ID)).thenReturn(Mono.error(new StoreException(StoreError.ORDER_NOT_FOUND)));
        web.get().uri("/orders/99").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("Order not found"));
                    assertTrue(html.contains("/cart/items"));
                });
        when(carts.changeWithReceipt(CART_ID, 7, CartAction.PLUS, null))
                .thenReturn(Mono.error(new StoreException(StoreError.CHECKOUT_IN_PROGRESS)));
        web.mutateWith(csrf()).post().uri("/cart/items").accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("id", "7").with("action", "PLUS"))
                .exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("CHECKOUT_IN_PROGRESS")
                .jsonPath("$.detail").isEqualTo(StoreError.CHECKOUT_IN_PROGRESS.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidFormParameters")
    void invalidFormBindingReturnsBadRequestInsteadOfAnInternalError(String id, String action, String quantity) {
        web.mutateWith(csrf()).post().uri("/cart/items").accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("id", id).with("action", action).with("quantity", quantity))
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.detail").isEqualTo("Invalid request parameters");
    }

    static Stream<Arguments> invalidFormParameters() {
        return Stream.of(Arguments.of("not-a-number", "PLUS", "1"), Arguments.of("7", "UNKNOWN", "1"),
                Arguments.of("0", "PLUS", "1"), Arguments.of("7", "SET", "fraction"));
    }

    @Test
    void uploadedCatalogUsesMultipartAndTheTokenFromTheRenderedForm() {
        when(importer.importJson(any())).thenReturn(Mono.just(new ImportResult(1, 0)));
        var seller = client(AccountRole.SELLER);
        var page = seller.get().uri("/catalog").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult();
        String html = Objects.requireNonNull(page.getResponseBody());
        var token = Pattern.compile("<input\\b(?=[^>]*\\bname=\"_csrf\")[^>]*\\bvalue=\"([^\"]+)\"").matcher(html);
        assertTrue(token.find(), "The catalog form must contain a CSRF token");
        var form = new MultipartBodyBuilder();
        form.part("catalog", "[]".getBytes(StandardCharsets.UTF_8)).filename("catalog.json")
                .contentType(MediaType.APPLICATION_JSON);
        form.part("_csrf", token.group(1));

        // Здесь нет csrf() mutator: проверяем настоящий multipart token resolver и cookie сессии.
        seller.post().uri("/catalog/import").accept(MediaType.TEXT_HTML)
                .cookies(cookies -> page.getResponseCookies().forEach((name, values) ->
                        values.forEach(cookie -> cookies.add(name, cookie.getValue()))))
                .body(BodyInserters.fromMultipartData(form.build())).exchange()
                .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/items");
    }

    @Test
    void availabilityReturnsExactMoneyStringsWithoutStartingCheckout() {
        when(checkout.availability(CART_ID)).thenReturn(Mono.just(new CheckoutAvailability(
                CheckoutAvailability.Status.AVAILABLE, "1000000000000000", "999999999999999", "0", CHECKOUT_KEY.toString())));
        web.get().uri("/cart/items/availability").accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("AVAILABLE")
                .jsonPath("$.balanceMinor").isEqualTo("1000000000000000")
                .jsonPath("$.requiredMinor").isEqualTo("999999999999999")
                .jsonPath("$.canBuy").isEqualTo(true).jsonPath("$.pending").isEqualTo(false)
                .jsonPath("$.checkoutKey").isEqualTo(CHECKOUT_KEY.toString());
        verify(checkout, never()).buy(any(), any(), anyLong());
    }

    @Test
    void newerPricesDisableCheckoutAndTheFormKeepsTheAmountActuallyShown() {
        var item = new ItemView(7, "test", "Keyboard", "Description", "/images/keyboard.svg", 2500, 2);
        when(carts.view(any())).thenReturn(Mono.just(new CartView(List.of(item))));
        when(checkout.availability(CART_ID)).thenReturn(Mono.just(new CheckoutAvailability(
                CheckoutAvailability.Status.AVAILABLE, "1000000", "600000", "0", CHECKOUT_KEY.toString())));
        web.get().uri("/cart/items").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("Цены изменились"));
                    assertTrue(html.contains("data-cart-total=\"5000\""));
                    var form = Pattern.compile("<form\\b[^>]*\\bdata-checkout\\b[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
                    assertTrue(form.find());
                    assertTrue(Pattern.compile("<button\\b[^>]*\\sdisabled(?:\\s|=|>)[^>]*>").matcher(form.group(1)).find());
                    assertTrue(Pattern.compile("<input\\b(?=[^>]*\\bname=\"amountMinor\")[^>]*\\bvalue=\"500000\"")
                            .matcher(form.group(1)).find());
                });
    }

    @Test
    void legacyOrderCannotDisplayPaymentSuccessFromAnUntrustedQueryFlag() {
        var order = new OrderView(42, Instant.parse("2026-10-12T10:00:00Z"), List.of(), 5000,
                OrderStatus.LEGACY_UNPAID, null);
        when(orders.get(42, CART_ID)).thenReturn(Mono.just(order));
        web.get().uri("/orders/42?newOrder=true").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("Без подтверждения оплаты"));
                    assertTrue(html.contains("Его сумма не учитывается в ваших расходах"));
                    assertFalse(html.contains("Заказ оформлен"));
                    assertFalse(html.contains("Оплата подтверждена"));
                });
    }

    @ParameterizedTest
    @MethodSource("movedPaths")
    void movedPrivatePagesStayReachableThroughSafeGetRedirectsOnly(String oldPath, String newPath) {
        anonymous.get().uri(oldPath).exchange().expectStatus().isTemporaryRedirect()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, newPath);
        // Небезопасный метод не переписывается: закрытый старый адрес остаётся закрытым даже с валидным CSRF.
        web.mutateWith(csrf()).post().uri(oldPath).exchange().expectStatus().isForbidden();
    }

    static Stream<Arguments> movedPaths() {
        return Stream.of(Arguments.of("/profile", "/cabinet"), Arguments.of("/profile/analytics?days=30", "/cabinet/analytics?days=30"),
                Arguments.of("/analytics", "/cabinet/analytics"), Arguments.of("/account/seller", "/cabinet/store"),
                Arguments.of("/account/seller/analytics/data?days=7", "/cabinet/store/analytics/data?days=7"));
    }

    @Test
    void recoveryPagesAreNeverCachedAndDoNotLeakTheTokenThroughReferrers() {
        when(recovery.available()).thenReturn(true);
        String token = "A".repeat(43);
        for (String path : List.of("/account/recovery", "/account/recovery/reset?token=" + token,
                "/account/recovery/reset?token=short")) {
            anonymous.get().uri(path).accept(MediaType.TEXT_HTML).exchange()
                    .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*no-store.*")
                    .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
        }
        anonymous.get().uri("/account/recovery/reset?token=" + token).accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isOk().expectBody(String.class).value(html -> {
                    assertTrue(Pattern.compile("<input\\b(?=[^>]*\\bname=\"token\")(?=[^>]*\\btype=\"hidden\")[^>]*\\bvalue=\""
                            + token + "\"").matcher(html).find());
                    // Страница не загружает чужие ресурсы, куда мог бы уйти адрес с токеном.
                    assertFalse(Pattern.compile("(?:src|href|action)=\"(?:https?:)?//").matcher(html).find());
                });
        anonymous.get().uri("/account/recovery/reset?token=short").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isBadRequest().expectBody(String.class)
                .value(html -> assertTrue(html.contains("Ссылка недействительна")));
    }

    @Test
    void unavailableRecoveryNeitherSendsNorClaimsThatAMessageWasSent() {
        when(recovery.available()).thenReturn(false);
        anonymous.get().uri("/account/recovery").accept(MediaType.TEXT_HTML).exchange().expectStatus().isEqualTo(503)
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("Восстановление пароля сейчас недоступно"));
                    assertFalse(html.contains("отправили"));
                });
        anonymous.mutateWith(csrf()).post().uri("/account/recovery").body(BodyInserters.fromFormData("email", "someone@example.test"))
                .exchange().expectStatus().isEqualTo(503);
        anonymous.mutateWith(csrf()).post().uri("/account/recovery/reset")
                .body(BodyInserters.fromFormData("token", "A".repeat(43)).with("password", "valid password long"))
                .exchange().expectStatus().isEqualTo(503);
        verify(recovery, never()).request(any());
        verify(recovery, never()).reset(any(), any());
    }

    @Test
    void recoveryFormsNeedCsrfAndValidInputBeforeReachingTheService() {
        when(recovery.available()).thenReturn(true);
        when(recovery.request(any())).thenReturn(Mono.empty());
        anonymous.post().uri("/account/recovery").body(BodyInserters.fromFormData("email", "someone@example.test"))
                .exchange().expectStatus().isForbidden();
        anonymous.mutateWith(csrf()).post().uri("/account/recovery").body(BodyInserters.fromFormData("email", "not-an-address"))
                .exchange().expectStatus().isBadRequest();
        anonymous.mutateWith(csrf()).post().uri("/account/recovery/reset")
                .body(BodyInserters.fromFormData("token", "A".repeat(43)).with("password", "short"))
                .exchange().expectStatus().isBadRequest();
        verify(recovery, never()).reset(any(), any());
        verify(recovery, never()).request(any());
        // Корректный запрос получает перенаправление без адреса в URL; тело ответа не зависит от аккаунта.
        anonymous.mutateWith(csrf()).post().uri("/account/recovery").body(BodyInserters.fromFormData("email", "someone@example.test"))
                .exchange().expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/account/recovery?sent");
    }

    private WebTestClient client(AccountRole role) {
        var principal = new StoreUserDetails(new StoreUser(UUID.randomUUID(), CART_ID,
                "role.user", "unused", "Role user", role, null));
        return anonymous.mutateWith(mockAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities())));
    }
}
