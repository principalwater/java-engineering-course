package dev.principalwater.store.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.DatabaseTestBase;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.dao.StoreIdentityRepository;
import dev.principalwater.store.model.*;

import dev.principalwater.store.service.CartService;
import dev.principalwater.store.service.CatalogImportService;
import dev.principalwater.store.service.CatalogService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.StepVerifier;

import static dev.principalwater.store.ProductFixtures.productInput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"store.cache.enabled=true", "store.cache.namespace=sprint07-cache-test",
        "store.cache.page-slots=1", "store.cache.operation-timeout=1s"})
class CatalogCacheIntegrationTest extends DatabaseTestBase {
    private static final CatalogQuery ALL = new CatalogQuery("", SortMode.NO, 1, 5);
    private static final CartView EMPTY_CART = new CartView(List.of());
    @Autowired private CatalogImportService importer;
    @Autowired private CatalogService catalog;
    @Autowired private CatalogReads reads;
    @Autowired private RedisProductCache productCache;
    @Autowired private CartService carts;
    @Autowired private ProductRepository products;
    @Autowired private StoreIdentityRepository identities;
    @Autowired private ReactiveRedisOperations<String, byte[]> redis;
    @Autowired private LettuceConnectionFactory connections;
    @Autowired private CatalogCacheProperties properties;
    @Autowired private ObjectMapper json;
    @Autowired private MeterRegistry metrics;
    @Autowired @Qualifier("cacheCodecScheduler") private Scheduler codecScheduler;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        String url = System.getenv("STORE_TEST_REDIS_URL");
        if (url == null || !(url.startsWith("redis://") || url.startsWith("rediss://"))) {
            throw new IllegalStateException("Cache integration tests require an isolated STORE_TEST_REDIS_URL");
        }
        registry.add("spring.data.redis.url", () -> url);
    }

    @BeforeEach
    @AfterEach
    void clearOwnRedisNamespace() {
        await(ownKeys().flatMap(keys -> keys.isEmpty() ? Mono.just(0L) : redis.delete(Flux.fromIterable(keys))));
    }

    @Test
    void listDetailsAndCartReusePublicProductsWithoutSharingQuantities() throws Exception {
        importItems(productInput("cache-alpha", "Яблоко <&> 🍎", "Alpha description", 137));
        long id = id("cache-alpha");
        UUID first = await(carts.identify(null));
        UUID second = await(carts.identify(null));
        await(carts.change(first, id, CartAction.SET, 2));
        await(carts.change(second, id, CartAction.SET, 4));
        double productHits = counter("product", "hit");
        var firstCart = await(carts.view(first));
        var secondCart = await(carts.view(second));
        assertEquals(274, firstCart.total());
        assertEquals(548, secondCart.total());
        assertEquals(2, await(catalog.item(id, firstCart)).count());
        assertEquals(4, await(catalog.item(id, secondCart)).count());
        assertEquals("Яблоко <&> 🍎", await(catalog.item(id, EMPTY_CART)).title());
        assertTrue(counter("product", "hit") > productHits);

        assertEquals(2, await(catalog.list(ALL, firstCart)).items().getFirst().count());
        double pageHits = counter("page", "hit");
        assertEquals(4, await(catalog.list(ALL, secondCart)).items().getFirst().count());
        assertTrue(counter("page", "hit") > pageHits);
        assertEquals(0, await(catalog.list(ALL, EMPTY_CART)).items().getFirst().count());
    }

    @Test
    void productIdsWithTheSameFormerSlotRemainCachedTogether() {
        var identity = await(identities.findById(StoreIdentity.SINGLETON_ID));
        // ID 100 и 342 делили слот 1521 из 2048: запись второго вытесняла первый без нехватки памяти.
        var first = new Product(100L, 0L, "cache-first", "First", "Description", ImageAsset.KEYBOARD, 100);
        var second = new Product(342L, 0L, "cache-second", "Second", "Description", ImageAsset.KEYBOARD, 200);
        var expected = Map.of(first.id().toString(), first, second.id().toString(), second);
        productCache.putAll(identity, expected).block(TIMEOUT);
        assertEquals(expected, await(productCache.getAll(identity, List.of("100", "342"))));
    }

    @Test
    void committedImportInvalidatesEveryDisplayEvenWhenAnOldFillArrivesLate() throws Exception {
        importItems(productInput("cache-alpha", "Before", "Alpha description", 100));
        long id = id("cache-alpha");
        UUID cartId = await(carts.identify(null));
        await(carts.change(cartId, id, CartAction.SET, 2));
        await(catalog.list(ALL, EMPTY_CART));
        var oldEntries = await(entries());

        importItems(productInput("cache-alpha", "After", "Updated description", 350));
        assertFreshDisplays(id, cartId, "After", 350);
        // Запоздалый fill имитирует запрос, начавшийся до commit: SQL revision делает его недоступным.
        restore(oldEntries);
        assertFreshDisplays(id, cartId, "After", 350);
    }

    @Test
    void failedSqlImportKeepsTheCommittedRevisionAndPreviouslyCachedProducts() throws Exception {
        importItems(productInput("cache-alpha", "Before", "Alpha description", 100));
        long id = id("cache-alpha");
        UUID cartId = await(carts.identify(null));
        await(carts.change(cartId, id, CartAction.SET, 2));
        await(catalog.list(ALL, EMPTY_CART));
        long revision = await(identities.findById(StoreIdentity.SINGLETON_ID)).catalogRevision();
        execute("ALTER TABLE products ADD CONSTRAINT reject_cached_import CHECK (sku <> 'cache-rejected')");
        try {
            byte[] payload = json.writeValueAsBytes(List.of(productInput("cache-alpha", "Partial", "Changed", 900),
                    productInput("cache-rejected", "Rejected", "Description", 200)));
            StepVerifier.create(importer.importJson(payload)).expectError(DataIntegrityViolationException.class).verify(TIMEOUT);
            assertEquals(revision, await(identities.findById(StoreIdentity.SINGLETON_ID)).catalogRevision());
            assertEquals(100, await(products.findById(id)).price());
            assertFreshDisplays(id, cartId, "Before", 100);
        } finally {
            execute("ALTER TABLE products DROP CONSTRAINT reject_cached_import");
        }
    }

    @Test
    void pageCollisionsExpiryAndMalformedValuesRemainMissesRatherThanWrongProducts() throws Exception {
        importItems(productInput("cache-alpha", "Alpha", "Description", 100),
                productInput("cache-beta", "Beta", "Description", 200));
        long alpha = id("cache-alpha");
        long beta = id("cache-beta");
        assertEquals(alpha, await(reads.product(alpha)).id());
        assertEquals(beta, await(reads.product(beta)).id());
        assertEquals(alpha, await(reads.product(alpha)).id());
        for (String search : List.of("Alpha", "Beta", "Alpha")) {
            var query = new CatalogQuery(search, SortMode.NO, 1, 5);
            assertEquals(search, await(catalog.list(query, EMPTY_CART)).items().getFirst().title());
        }
        var keys = await(ownKeys());
        assertTrue(keys.stream().filter(key -> key.contains(":page:")).count() <= properties.pageSlots(),
                "Search page keyspace must remain bounded");
        for (String key : keys) {
            Duration ttl = await(redis.getExpire(key));
            assertTrue(ttl.isPositive() && ttl.compareTo(properties.ttl()) <= 0);
            assertTrue(await(redis.expire(key, Duration.ofMillis(1))));
            // Redis использует реальные часы; ускоряем expiry серверной командой и ждём наблюдаемого отсутствия.
            StepVerifier.create(redis.hasKey(key).repeatWhen(completed -> completed.delayElements(Duration.ofMillis(10)))
                    .filter(present -> !present).next()).expectNext(false).expectComplete().verify(TIMEOUT);
        }
        assertEquals(100, await(reads.product(alpha)).price());
        assertEquals(2, await(catalog.list(ALL, EMPTY_CART)).totalItems());
        var malformed = new HashMap<String, byte[]>();
        for (String key : await(ownKeys())) {
            malformed.put(key, new byte[]{'{'});
        }
        restore(malformed);
        double failures = counter("product", "failure");
        assertEquals(100, await(reads.product(alpha)).price());
        assertTrue(counter("product", "failure") > failures, "The existing product value must be decoded and rejected");
        assertEquals(2, await(catalog.list(ALL, EMPTY_CART)).totalItems());
    }

    @Test
    void oversizedValuesBypassAdmissionAndReloadWithoutTruncatingTheProduct() throws Exception {
        importItems(productInput("cache-alpha", "Alpha", "Description", 100));
        var product = await(products.findById(id("cache-alpha")));
        var identity = await(identities.findById(StoreIdentity.SINGLETON_ID));
        var small = new CatalogCacheProperties(true, properties.namespace(), properties.ttl(), properties.operationTimeout(),
                1, properties.pageSlots());
        var cache = new RedisProductCache(redis, json, small, codecScheduler, metrics);
        double skipped = counter("product", "skipped");
        cache.put(identity, product.id().toString(), product).block(TIMEOUT);
        StepVerifier.create(cache.get(identity, product.id().toString())).expectComplete().verify(TIMEOUT);
        assertTrue(await(ownKeys()).isEmpty());
        assertTrue(counter("product", "skipped") > skipped);
        await(reads.product(product.id()));
        String key = await(ownKeys()).getFirst();
        await(redis.opsForValue().set(key, new byte[properties.maxEntryBytes() + 1], properties.ttl()));
        skipped = counter("product", "skipped");
        assertEquals(product, await(reads.product(product.id())));
        assertTrue(counter("product", "skipped") > skipped);
    }

    @Test
    void cacheClientOutageFallsBackAndRecoveryCannotResurrectAnOlderCommittedCatalog() throws Exception {
        importItems(productInput("cache-alpha", "Before", "Description", 100));
        long id = id("cache-alpha");
        UUID cartId = await(carts.identify(null));
        await(carts.change(cartId, id, CartAction.SET, 2));
        await(catalog.list(ALL, EMPTY_CART));
        var oldEntries = await(entries());
        double failures = counter("product", "failure");
        connections.stop();
        try {
            assertEquals(100, await(reads.product(id)).price());
            importItems(productInput("cache-alpha", "After outage", "Description", 500));
            assertFreshDisplays(id, cartId, "After outage", 500);
            assertTrue(counter("product", "failure") > failures);
            execute("ALTER TABLE products RENAME TO unavailable_products");
            try {
                StepVerifier.create(reads.product(id)).expectError(DataAccessException.class).verify(TIMEOUT);
            } finally {
                execute("ALTER TABLE unavailable_products RENAME TO products");
            }
        } finally {
            connections.start();
        }
        assertTrue(await(redis.hasKey(oldEntries.keySet().iterator().next())));
        assertFreshDisplays(id, cartId, "After outage", 500);
    }

    private void assertFreshDisplays(long id, UUID cartId, String title, long price) {
        var cart = await(carts.view(cartId));
        assertEquals(2 * price, cart.total());
        var item = await(catalog.item(id, cart));
        assertEquals(title, item.title());
        assertEquals(price, item.price());
        assertEquals(2, item.count());
        var page = await(catalog.list(ALL, cart));
        assertEquals(title, page.items().getFirst().title());
        assertEquals(price, page.items().getFirst().price());
    }

    private Mono<List<String>> ownKeys() {
        return redis.scan(ScanOptions.scanOptions().match(properties.namespace() + ":*").count(100).build()).collectList();
    }

    private Mono<Map<String, byte[]>> entries() {
        return ownKeys().flatMapMany(Flux::fromIterable).concatMap(key ->
                redis.opsForValue().get(key).map(value -> Map.entry(key, value))).collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private void restore(Map<String, byte[]> entries) {
        Flux.fromIterable(entries.entrySet()).concatMap(entry ->
                redis.opsForValue().set(entry.getKey(), entry.getValue(), properties.ttl())).then().block(TIMEOUT);
    }

    private double counter(String kind, String outcome) {
        return metrics.get("store.cache.entries").tags("kind", kind, "outcome", outcome).counter().count();
    }

    private void importItems(ProductInput... inputs) throws Exception {
        await(importer.importJson(json.writeValueAsBytes(List.of(inputs))));
    }

    private long id(String sku) {
        return await(products.findAllBySkuIn(List.of(sku)).single()).id();
    }
}
