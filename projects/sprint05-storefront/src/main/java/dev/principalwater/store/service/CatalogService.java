package dev.principalwater.store.service;

import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogPage;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ItemView;
import dev.principalwater.store.model.Paging;
import dev.principalwater.store.model.SortMode;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private static final String ID_PROPERTY = "id";
    private static final String TITLE_PROPERTY = "title";
    private static final char LIKE_ESCAPE = '!';
    private static final String MANY_CHARACTERS = "%";
    private static final String ONE_CHARACTER = "_";
    private final ProductRepository products;

    public CatalogService(ProductRepository products) {
        this.products = products;
    }

    public CatalogPage list(CatalogQuery query, CartView cart) {
        String pattern = containsPattern(query.search().toLowerCase(Locale.ROOT));
        Specification<dev.principalwater.store.model.Product> filter = (root, statement, builder) ->
                builder.or(builder.like(builder.lower(root.get(TITLE_PROPERTY)), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get("description")), pattern, LIKE_ESCAPE));
        var page = products.findAll(filter, PageRequest.of(query.pageNumber() - 1, query.pageSize(), sort(query.sort())));
        var counts = cart.counts();
        List<ItemView> items = page.getContent().stream()
                .map(product -> ItemView.from(product, counts.getOrDefault(product.getId(), 0))).toList();
        var paging = new Paging(query.pageSize(), query.pageNumber(), query.pageNumber() > 1, page.hasNext());
        return new CatalogPage(items, paging, page.getTotalElements());
    }

    public ItemView item(long id, CartView cart) {
        var product = products.findById(id).orElseThrow(() -> new StoreException(StoreError.PRODUCT_NOT_FOUND));
        return ItemView.from(product, cart.counts().getOrDefault(id, 0));
    }

    private Sort sort(SortMode mode) {
        var stable = Sort.Order.asc(ID_PROPERTY);
        return switch (mode) {
            case NO -> Sort.by(stable);
            case ALPHA -> Sort.by(Sort.Order.asc(TITLE_PROPERTY).ignoreCase(), stable);
            case PRICE -> Sort.by(Sort.Order.asc("price"), stable);
        };
    }

    private String containsPattern(String search) {
        String escape = String.valueOf(LIKE_ESCAPE);
        // LIKE-метасимволы ищутся буквально; значения остаются bind-параметрами Hibernate.
        return MANY_CHARACTERS + search.replace(escape, escape + escape)
                .replace(MANY_CHARACTERS, escape + MANY_CHARACTERS)
                .replace(ONE_CHARACTER, escape + ONE_CHARACTER) + MANY_CHARACTERS;
    }
}
