package dev.principalwater.store.service;

import dev.principalwater.store.dao.CatalogQueries;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.CartView;
import dev.principalwater.store.model.CatalogPage;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ItemView;
import dev.principalwater.store.model.Paging;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class CatalogService {
    private final ProductRepository products;
    private final CatalogQueries queries;

    public CatalogService(ProductRepository products, CatalogQueries queries) {
        this.products = products;
        this.queries = queries;
    }

    public Mono<CatalogPage> list(CatalogQuery query, CartView cart) {
        var counts = cart.counts();
        return queries.list(query).map(product -> ItemView.from(product, counts.getOrDefault(product.id(), 0)))
                .collectList().flatMap(items -> queries.count(query).map(total -> new CatalogPage(items,
                        new Paging(query.pageSize(), query.pageNumber(), query.pageNumber() > 1,
                                (long) query.pageNumber() * query.pageSize() < total), total)));
    }

    public Mono<ItemView> item(long id, CartView cart) {
        return products.findById(id).switchIfEmpty(Mono.error(new StoreException(StoreError.PRODUCT_NOT_FOUND)))
                .map(product -> ItemView.from(product, cart.counts().getOrDefault(id, 0)));
    }
}
