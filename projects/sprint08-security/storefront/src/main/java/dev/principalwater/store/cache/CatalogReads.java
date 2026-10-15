package dev.principalwater.store.cache;

import dev.principalwater.store.dao.CatalogQueries;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.dao.StoreIdentityRepository;
import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreIdentity;
import dev.principalwater.store.model.StoreLimits;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class CatalogReads {
    private final ProductRepository products;
    private final CatalogQueries queries;
    private final StoreIdentityRepository identities;
    private final RedisProductCache productCache;
    private final RedisCatalogPageCache pageCache;

    public CatalogReads(ProductRepository products, CatalogQueries queries, StoreIdentityRepository identities,
                        RedisProductCache productCache, RedisCatalogPageCache pageCache) {
        this.products = products;
        this.queries = queries;
        this.identities = identities;
        this.productCache = productCache;
        this.pageCache = pageCache;
    }

    public Mono<CatalogSnapshot> page(CatalogQuery query) {
        String key = query.sort().name() + ":" + query.pageNumber() + ":" + query.pageSize()
                + ":" + query.search().toLowerCase(Locale.ROOT);
        return identity().flatMap(identity -> pageCache.get(identity, key).switchIfEmpty(Mono.defer(() ->
                queries.list(query).collectList().flatMap(items -> queries.count(query)
                        .map(total -> new CatalogSnapshot(items, total)))
                        .flatMap(page -> pageCache.put(identity, key, page).thenReturn(page)))));
    }

    public Mono<Product> product(long id) {
        return products(List.of(id)).flatMap(found -> Mono.justOrEmpty(found.get(id)))
                .switchIfEmpty(Mono.error(new StoreException(StoreError.PRODUCT_NOT_FOUND)));
    }

    public Mono<Map<Long, Product>> products(Collection<Long> ids) {
        var selected = ids.stream().distinct().toList();
        if (selected.isEmpty()) {
            return Mono.just(Map.of());
        }
        if (selected.size() > StoreLimits.MAX_CART_ITEMS) {
            return Mono.error(new IllegalArgumentException("Product batch exceeds the cart limit"));
        }
        return identity().flatMap(identity -> {
            var keys = selected.stream().map(Object::toString).toList();
            return productCache.getAll(identity, keys).flatMap(cached -> {
                var missing = selected.stream().filter(id -> !cached.containsKey(id.toString())).toList();
                var loaded = missing.isEmpty() ? Mono.<Map<String, Product>>just(Map.of())
                        : products.findAllById(missing).collectMap(product -> product.id().toString());
                return loaded.flatMap(found -> productCache.putAll(identity, found).then(Mono.fromSupplier(() -> {
                    var result = new HashMap<Long, Product>();
                    cached.values().forEach(product -> result.put(product.id(), product));
                    found.values().forEach(product -> result.put(product.id(), product));
                    return Map.copyOf(result);
                })));
            });
        });
    }

    private Mono<StoreIdentity> identity() {
        // SQL revision меняется вместе с импортом: старый fill не станет свежим даже после отказа Redis.
        return identities.findById(StoreIdentity.SINGLETON_ID)
                .switchIfEmpty(Mono.error(new IllegalStateException("Store dataset identity is missing")));
    }
}
