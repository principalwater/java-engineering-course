package dev.principalwater.store.controller;

import dev.principalwater.store.model.CartMutationInput;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogNavigation;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogService;
import dev.principalwater.store.util.StoreRedirects;
import jakarta.validation.Valid;
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
import org.springframework.web.reactive.result.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Controller
public class CatalogController {
    private final CatalogService catalog;
    private final CartService carts;

    public CatalogController(CatalogService catalog, CartService carts) {
        this.catalog = catalog;
        this.carts = carts;
    }

    // defaultValue аннотации требует compile-time String: enum/getter здесь не компилируется.
    @GetMapping({"/", StorePaths.ITEMS})
    public Mono<String> items(@RequestParam(defaultValue = CatalogQuery.DEFAULT_SEARCH) String search,
                              @RequestParam(defaultValue = CatalogQuery.DEFAULT_SORT) SortMode sort,
                              @RequestParam(defaultValue = CatalogQuery.DEFAULT_PAGE_NUMBER) int pageNumber,
                              @RequestParam(defaultValue = CatalogQuery.DEFAULT_PAGE_SIZE) int pageSize,
                              @ModelAttribute("cart") CartView cart, Model model) {
        var query = new CatalogQuery(search, sort, pageNumber, pageSize);
        return catalog.list(query, cart).map(page -> {
            model.addAttribute("query", query);
            model.addAttribute("items", page.items());
            model.addAttribute("paging", page.paging());
            model.addAttribute("totalItems", page.totalItems());
            return "items";
        });
    }

    @GetMapping(StorePaths.ITEMS + "/{id}")
    public Mono<String> item(@PathVariable @Positive long id, @ModelAttribute("cart") CartView cart, Model model) {
        return catalog.item(id, cart).map(item -> {
            model.addAttribute("item", item);
            return "item";
        });
    }

    @PostMapping(StorePaths.ITEMS)
    public Mono<RedirectView> changeFromFeed(@Valid @ModelAttribute CartMutationInput input,
                                            @ModelAttribute CatalogNavigation navigation,
                                            @RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId) {
        var query = navigation.query();
        // URI-переменная сохраняет '+': C++ не превращается после редиректа в C с пробелами.
        String target = UriComponentsBuilder.fromPath("/items").queryParam("search", "{search}")
                .queryParam("sort", query.sort()).queryParam("pageNumber", query.pageNumber())
                .queryParam("pageSize", query.pageSize()).encode().buildAndExpand(query.search()).toUriString();
        return carts.change(cartId, input.id(), input.action(), input.quantity()).thenReturn(StoreRedirects.to(target));
    }

    @PostMapping(StorePaths.ITEMS + "/{id}")
    public Mono<RedirectView> changeFromItem(@PathVariable @Positive long id,
                                            @Valid @ModelAttribute CartMutationInput input,
                                            @RequestAttribute(CartIdentityFilter.ATTRIBUTE_NAME) UUID cartId) {
        return carts.change(cartId, id, input.action(), input.quantity()).thenReturn(StoreRedirects.to("/items/" + id));
    }
}
