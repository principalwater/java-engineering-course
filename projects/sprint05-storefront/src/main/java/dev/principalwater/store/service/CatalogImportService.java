package dev.principalwater.store.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.principalwater.store.dao.ProductRepository;
import dev.principalwater.store.model.ImportResult;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.ProductInput;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreLimits;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogImportService {
    private final ProductRepository products;
    private final Validator validator;
    private final ObjectReader reader;

    public CatalogImportService(ProductRepository products, Validator validator, ObjectMapper json) {
        this.products = products;
        this.validator = validator;
        reader = json.readerFor(new TypeReference<List<ProductInput>>() {})
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    @Transactional
    public ImportResult importJson(byte[] bytes) {
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
        // Полный файл проверяется до первой записи, чтобы ошибочная строка не меняла часть каталога.
        for (ProductInput input : inputs) {
            if (input == null || !validator.validate(input).isEmpty() || !skus.add(input.sku())) {
                throw new StoreException(StoreError.INVALID_CATALOG, "Invalid item or duplicate SKU");
            }
        }
        Map<String, Product> existing = products.findAllBySkuIn(skus).stream()
                .collect(Collectors.toMap(Product::getSku, Function.identity()));
        int added = 0;
        for (ProductInput input : inputs) {
            Product product = existing.get(input.sku());
            if (product == null) {
                products.save(new Product(input));
                added++;
            } else {
                product.update(input);
            }
        }
        return new ImportResult(added, inputs.size() - added);
    }
}
