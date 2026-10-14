package dev.principalwater.store.controller;

import dev.principalwater.store.TestBase;
import dev.principalwater.store.runtime.ResourceAdmissionFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import dev.principalwater.store.config.SecurityConfig;
import dev.principalwater.store.util.DisplayFormats;
import dev.principalwater.store.service.*;
import dev.principalwater.store.config.WebConfig;
import dev.principalwater.store.model.*;

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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

@WebFluxTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ResourceAdmissionFilter.class),
        controllers = {CatalogController.class, CartController.class, OrderController.class,
        CatalogImportController.class, ProfileController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, WebConfig.class, DisplayFormats.class})
class StoreWebTest extends TestBase {
    @MockitoBean private AccountService accounts;
    private static final UUID CART_ID = UUID.fromString("652b77b8-9b3f-498c-9b99-321bd2fd3949");
    private static final CartView EMPTY_CART = new CartView(List.of());
    @Autowired private WebTestClient web;
    @MockitoBean private CartService carts;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private CheckoutService checkout;
    @MockitoBean private OrderService orders;
    @MockitoBean private CatalogImportService importer;

    @BeforeEach
    void cartOwner() {
        when(carts.identify(any())).thenReturn(Mono.just(CART_ID));
        when(carts.view(any())).thenReturn(Mono.just(EMPTY_CART));
        when(carts.change(any(), anyLong(), any(), any())).thenReturn(Mono.just(EMPTY_CART));
    }

    @Test
    void feedUsesDefaultParametersAndEscapesProductText() {
        var item = new ItemView(7, "test", "<script>alert(1)</script>", "<img src=x onerror=alert(2)>",
                "/images/cup.svg", 2500, 0);
        when(catalog.list(any(), any())).thenReturn(Mono.just(new CatalogPage(List.of(item), new Paging(5, 1, false, false), 1)));

        web.get().uri("/").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectCookie().httpOnly("storefront-cart", true).expectBody(String.class).value(html -> {
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
        var order = new OrderView(42, purchasedAt, List.of(line), 5000);
        when(orders.list(CART_ID)).thenReturn(Mono.just(List.of(order)));
        when(orders.get(42, CART_ID)).thenReturn(Mono.just(order));
        when(orders.profile(CART_ID)).thenReturn(Mono.just(new ProfileView(1, 5000, 2, purchasedAt)));

        web.get().uri("/items/7").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("Keyboard")));
        web.get().uri("/cart/items").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("data-cart-total=\"5000\"")));
        web.get().uri("/orders").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertTrue(html.contains("/orders/42")));
        web.get().uri("/orders/42?newOrder=true").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("Заказ оформлен"));
                    assertTrue(html.contains("12.10.2026, 13:00"));
                    assertTrue(html.contains("data-order-total=\"5000\""));
                });
        web.get().uri("/profile").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> {
                    assertTrue(html.contains("12.10.2026, 13:00"));
                    assertTrue(html.contains("/profile/analytics"));
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
        when(carts.change(CART_ID, 7, CartAction.SET, 3))
                .thenReturn(Mono.just(new CartView(List.of(item))));
        web.mutateWith(csrf()).post().uri("/cart/items").accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("id", "7").with("action", "SET").with("quantity", "3"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.itemId").isEqualTo(7).jsonPath("$.count").isEqualTo(3)
                .jsonPath("$.cart.total").isEqualTo(7500).jsonPath("$.cart.itemCount").isEqualTo(3);
    }

    @Test
    void checkoutNeedsCsrfAndRedirectsToTheNewOrder() {
        when(checkout.buy(CART_ID)).thenReturn(Mono.just(42L));
        web.post().uri("/buy").accept(MediaType.TEXT_HTML).exchange().expectStatus().isForbidden();
        web.mutateWith(csrf()).post().uri("/buy").exchange().expectStatus().isSeeOther()
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
        var page = web.get().uri("/catalog").accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult();
        String html = Objects.requireNonNull(page.getResponseBody());
        var token = Pattern.compile("<input\\b(?=[^>]*\\bname=\"_csrf\")[^>]*\\bvalue=\"([^\"]+)\"").matcher(html);
        assertTrue(token.find(), "The catalog form must contain a CSRF token");
        var form = new MultipartBodyBuilder();
        form.part("catalog", "[]".getBytes(StandardCharsets.UTF_8)).filename("catalog.json")
                .contentType(MediaType.APPLICATION_JSON);
        form.part("_csrf", token.group(1));

        // Здесь нет csrf() mutator: проверяем настоящий multipart token resolver и cookie сессии.
        web.post().uri("/catalog/import").accept(MediaType.TEXT_HTML)
                .cookies(cookies -> page.getResponseCookies().forEach((name, values) ->
                        values.forEach(cookie -> cookies.add(name, cookie.getValue()))))
                .body(BodyInserters.fromMultipartData(form.build())).exchange()
                .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/items");
    }
}
