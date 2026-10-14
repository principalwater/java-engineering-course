package dev.principalwater.store.dao;

import dev.principalwater.store.model.CatalogQuery;
import dev.principalwater.store.model.ImageAsset;
import dev.principalwater.store.model.Product;
import dev.principalwater.store.model.SortMode;
import java.util.Locale;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class CatalogQueries {
    private static final String FILTER = "LOWER(title) LIKE :pattern ESCAPE '!' OR LOWER(description) LIKE :pattern ESCAPE '!'";
    private static final String LIKE_ESCAPE = "!";
    private static final String MANY_CHARACTERS = "%";
    private static final String ONE_CHARACTER = "_";
    private final DatabaseClient database;

    public CatalogQueries(DatabaseClient database) {
        this.database = database;
    }

    public Flux<Product> list(CatalogQuery query) {
        String sql = "SELECT * FROM products WHERE (" + FILTER + ") ORDER BY " + order(query.sort())
                + " LIMIT :limit OFFSET :offset";
        return database.sql(sql).bind("pattern", pattern(query.search())).bind("limit", query.pageSize())
                .bind("offset", (long) (query.pageNumber() - 1) * query.pageSize())
                .map((row, metadata) -> new Product(row.get("id", Long.class), row.get("version", Long.class),
                        row.get("sku", String.class), row.get("title", String.class),
                        row.get("description", String.class), ImageAsset.valueOf(row.get("image", String.class)),
                        row.get("price", Long.class))).all();
    }

    public Mono<Long> count(CatalogQuery query) {
        return database.sql("SELECT COUNT(*) AS total FROM products WHERE (" + FILTER + ")")
                .bind("pattern", pattern(query.search())).map((row, metadata) -> row.get("total", Long.class)).one();
    }

    private String order(SortMode mode) {
        // Идентификаторы SQL выбираются из закрытого enum, значения пользователя связываются параметрами.
        return switch (mode) {
            case NO -> "id ASC";
            case ALPHA -> "LOWER(title) ASC, id ASC";
            case PRICE -> "price ASC, id ASC";
        };
    }

    private String pattern(String search) {
        return MANY_CHARACTERS + search.toLowerCase(Locale.ROOT).replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace(MANY_CHARACTERS, LIKE_ESCAPE + MANY_CHARACTERS)
                .replace(ONE_CHARACTER, LIKE_ESCAPE + ONE_CHARACTER) + MANY_CHARACTERS;
    }
}
