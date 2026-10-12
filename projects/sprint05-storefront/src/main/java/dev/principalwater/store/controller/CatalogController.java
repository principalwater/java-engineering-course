package dev.principalwater.store.controller;

import dev.principalwater.store.model.CartAction;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogService;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class CatalogController {
    private final CatalogService catalog;
    private final CartService carts;

    public CatalogController(CatalogService catalog, CartService carts) {
        this.catalog = catalog;
        this.carts = carts;
    }

    @GetMapping({"/", "/items"})
    public String items(@RequestParam(defaultValue = "") String search,
                        @RequestParam(defaultValue = "NO") SortMode sort,
                        @RequestParam(defaultValue = "1") int pageNumber,
                        @RequestParam(defaultValue = "5") int pageSize,
                        @ModelAttribute("cart") CartView cart, Model model) {
        var query = new CatalogQuery(search, sort, pageNumber, pageSize);
        var page = catalog.list(query, cart);
        model.addAttribute("query", query);
        model.addAttribute("items", page.items());
        model.addAttribute("paging", page.paging());
        model.addAttribute("totalItems", page.totalItems());
        return "items";
    }

    @GetMapping("/items/{id}")
    public String item(@PathVariable @Positive long id, @ModelAttribute("cart") CartView cart, Model model) {
        model.addAttribute("item", catalog.item(id, cart));
        return "item";
    }

    @PostMapping("/items")
    public RedirectView changeFromFeed(@RequestParam @Positive long id, @RequestParam CartAction action,
                                       @RequestParam(defaultValue = "") String search,
                                       @RequestParam(defaultValue = "NO") SortMode sort,
                                       @RequestParam(defaultValue = "1") int pageNumber,
                                       @RequestParam(defaultValue = "5") int pageSize,
                                       @RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId) {
        var query = new CatalogQuery(search, sort, pageNumber, pageSize);
        carts.change(cartId, id, action);
        // URI-переменная кодирует также '+': иначе C++ после редиректа превращается в C с пробелами.
        String target = UriComponentsBuilder.fromPath("/items").queryParam("search", "{search}")
                .queryParam("sort", query.sort()).queryParam("pageNumber", query.pageNumber())
                .queryParam("pageSize", query.pageSize()).encode().buildAndExpand(query.search()).toUriString();
        return StoreRedirects.to(target);
    }

    @PostMapping("/items/{id}")
    public RedirectView changeFromItem(@PathVariable @Positive long id, @RequestParam CartAction action,
                                       @RequestAttribute(CartIdentityInterceptor.ATTRIBUTE_NAME) UUID cartId) {
        carts.change(cartId, id, action);
        return StoreRedirects.to("/items/" + id);
    }
}
