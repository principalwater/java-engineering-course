package dev.principalwater.store.controller;

import dev.principalwater.store.config.SecurityConfig;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogPage;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ImportResult;
import dev.principalwater.store.model.ItemView;
import dev.principalwater.store.model.OrderView;
import dev.principalwater.store.model.Paging;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogImportService;
import dev.principalwater.store.service.CatalogService;
import dev.principalwater.store.service.CheckoutService;
import dev.principalwater.store.service.OrderService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = {CatalogController.class, CartController.class, OrderController.class, CatalogImportController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, DisplayFormats.class})
class StoreWebTest {
    private static final UUID CART_ID = UUID.fromString("652b77b8-9b3f-498c-9b99-321bd2fd3949");
    @Autowired private MockMvc mvc;
    @MockitoBean private CartService carts;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private CheckoutService checkout;
    @MockitoBean private OrderService orders;
    @MockitoBean private CatalogImportService importer;

    @BeforeEach
    void cartOwner() {
        when(carts.identify(any())).thenReturn(CART_ID);
        when(carts.view(any())).thenReturn(new CartView(List.of()));
    }

    @Test
    void feedUsesDefaultParametersAndEscapesProductText() throws Exception {
        var item = new ItemView(7, "test", "<script>alert(1)</script>", "Description", "/images/cup.svg", 2500, 0);
        when(catalog.list(any(), any())).thenReturn(new CatalogPage(List.of(item), new Paging(5, 1, false, false), 1));

        var result = mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("items"))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>")))).andReturn();
        var query = (CatalogQuery) result.getModelAndView().getModel().get("query");
        assertEquals("", query.search());
        assertEquals(SortMode.NO, query.sort());
        assertEquals(1, query.pageNumber());
        assertEquals(5, query.pageSize());
    }

    @Test
    void itemCartAndOrderPagesRenderTheirModels() throws Exception {
        var item = new ItemView(7, "test", "Keyboard", "Description", "/images/keyboard.svg", 2500, 2);
        when(catalog.item(anyLong(), any())).thenReturn(item);
        when(carts.view(any())).thenReturn(new CartView(List.of(item)));
        var order = new OrderView(42, Instant.parse("2026-10-12T10:00:00Z"), List.of(item), 5000);
        when(orders.list(CART_ID)).thenReturn(List.of(order));
        when(orders.get(42, CART_ID)).thenReturn(order);

        mvc.perform(get("/items/7")).andExpect(status().isOk()).andExpect(view().name("item"))
                .andExpect(content().string(containsString("Keyboard")));
        mvc.perform(get("/cart/items")).andExpect(status().isOk()).andExpect(view().name("cart"))
                .andExpect(content().string(containsString("data-cart-total=\"5000\"")));
        mvc.perform(get("/orders")).andExpect(status().isOk()).andExpect(view().name("orders"));
        mvc.perform(get("/orders/42").param("newOrder", "true")).andExpect(status().isOk())
                .andExpect(view().name("order")).andExpect(content().string(containsString("Заказ оформлен")))
                .andExpect(content().string(containsString("12.10.2026, 13:00")));
    }

    @Test
    void cartMutationPreservesLiteralSearchCharactersInTheRedirect() throws Exception {
        mvc.perform(post("/items").with(csrf()).param("id", "7").param("action", "PLUS")
                        .param("search", "C++ & blue").param("sort", "ALPHA").param("pageNumber", "2").param("pageSize", "5"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/items?search=C%2B%2B%20%26%20blue&sort=ALPHA&pageNumber=2&pageSize=5"));
    }

    @Test
    void checkoutNeedsCsrfAndRedirectsToTheNewOrder() throws Exception {
        when(checkout.buy(CART_ID)).thenReturn(42L);
        mvc.perform(post("/buy")).andExpect(status().isForbidden());
        mvc.perform(post("/buy").with(csrf())).andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/orders/42?newOrder=true"));
    }

    @Test
    void invalidParametersAndForeignOrdersReturnSafeErrors() throws Exception {
        mvc.perform(get("/items").param("sort", "CUSTOM")).andExpect(status().isBadRequest());
        mvc.perform(get("/items").param("pageSize", "3")).andExpect(status().isBadRequest());
        mvc.perform(get("/items/-1")).andExpect(status().isBadRequest());
        when(orders.get(99, CART_ID)).thenThrow(new StoreException(StoreError.ORDER_NOT_FOUND));
        mvc.perform(get("/orders/99")).andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Order not found")));
    }

    @Test
    void uploadedCatalogUsesTheMultipartFieldAndRedirectsToTheFeed() throws Exception {
        when(importer.importJson(any())).thenReturn(new ImportResult(1, 0));
        var file = new MockMultipartFile("catalog", "catalog.json", "application/json", "[]".getBytes());
        mvc.perform(multipart("/catalog/import").file(file).with(csrf()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/items"));
        mvc.perform(get("/catalog")).andExpect(status().isOk()).andExpect(view().name("catalog"))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
