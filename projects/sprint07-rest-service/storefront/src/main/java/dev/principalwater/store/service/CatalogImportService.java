package dev.principalwater.store.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.*;

import jakarta.validation.Validator;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class CatalogImportService {
    private final ProductRepository products;
    private final Validator validator;
    private final ObjectReader reader;
    private final TransactionalOperator transactions;

    public CatalogImportService(ProductRepository products, Validator validator, ObjectMapper json,
                                TransactionalOperator transactions) {
        this.products = products;
        this.validator = validator;
        this.transactions = transactions;
        reader = json.readerFor(new TypeReference<List<ProductInput>>() {})
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public Mono<ImportResult> importJson(byte[] bytes) {
        // Ограниченный CPU-разбор JSON выполняется вне HTTP event loop; SQL остаётся полностью R2DBC.
        return Mono.fromCallable(() -> validate(bytes)).subscribeOn(Schedulers.boundedElastic())
                .flatMap(inputs -> {
                    var skus = inputs.stream().map(ProductInput::sku).toList();
                    return products.findAllBySkuIn(skus).collectMap(Product::sku).flatMap(existing ->
                            Flux.fromIterable(inputs).concatMap(input -> {
                                Product product = existing.get(input.sku());
                                return products.save(product == null ? new Product(input) : product.update(input));
                            }).then(Mono.fromSupplier(() -> new ImportResult(inputs.size() - existing.size(), existing.size()))))
                            .as(transactions::transactional);
                });
    }

    private List<ProductInput> validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new StoreException(StoreError.INVALID_CATALOG);
        }
        if (bytes.length > StoreLimits.MAX_IMPORT_BYTES) {
            throw new StoreException(StoreError.CATALOG_TOO_LARGE);
        }
        List<ProductInput> inputs;
        try {
            inputs = reader.readValue(bytes);
        } catch (IOException exception) {
            throw new StoreException(StoreError.INVALID_CATALOG, "Expected a complete JSON array of products");
        }
        if (inputs == null || inputs.isEmpty() || inputs.size() > StoreLimits.MAX_IMPORT_ITEMS) {
            throw new StoreException(StoreError.INVALID_CATALOG);
        }
        var skus = new HashSet<String>();
        // Полная проверка предшествует транзакции: ошибочная строка не меняет часть каталога.
        for (ProductInput input : inputs) {
            if (input == null || !validator.validate(input).isEmpty() || !skus.add(input.sku())) {
                throw new StoreException(StoreError.INVALID_CATALOG, "Invalid item or duplicate SKU");
            }
        }
        return inputs;
    }
}
