package dev.principalwater.store.service;

import dev.principalwater.store.cache.CatalogReads;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogPage;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ItemView;
import dev.principalwater.store.model.Paging;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class CatalogService {
    private final CatalogReads reads;

    public CatalogService(CatalogReads reads) {
        this.reads = reads;
    }

    public Mono<CatalogPage> list(CatalogQuery query, CartView cart) {
        var counts = cart.counts();
        return reads.page(query).map(page -> new CatalogPage(page.products().stream()
                        .map(product -> ItemView.from(product, counts.getOrDefault(product.id(), 0))).toList(),
                        new Paging(query.pageSize(), query.pageNumber(), query.pageNumber() > 1,
                                (long) query.pageNumber() * query.pageSize() < page.totalItems()), page.totalItems()));
    }

    public Mono<ItemView> item(long id, CartView cart) {
        return reads.product(id)
                .map(product -> ItemView.from(product, cart.counts().getOrDefault(id, 0)));
    }
}
