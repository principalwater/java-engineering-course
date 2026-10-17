package dev.principalwater.store.controller;

import dev.principalwater.store.util.StoreRedirects;
import dev.principalwater.store.model.ImportResult;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.model.StoreLimits;
import dev.principalwater.store.service.CatalogImportService;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.reactive.result.view.RedirectView;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
public class CatalogImportController {
    private final CatalogImportService importer;
    private final byte[] demo;

    public CatalogImportController(CatalogImportService importer) throws IOException {
        this.importer = importer;
        // Маленький bundled-файл читается один раз при создании бина, до приёма HTTP-запросов.
        demo = new ClassPathResource("static/catalog-demo.json").getContentAsByteArray();
    }

    @GetMapping(StorePaths.CATALOG)
    public String catalog() {
        return "catalog";
    }

    @PostMapping(StorePaths.CATALOG + "/import")
    public Mono<RedirectView> upload(@RequestPart("catalog") Mono<FilePart> file, ServerWebExchange exchange) {
        return file.flatMap(part -> DataBufferUtils.join(part.content(), StoreLimits.MAX_IMPORT_BYTES))
                .switchIfEmpty(Mono.error(new StoreException(StoreError.INVALID_CATALOG)))
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                }).flatMap(importer::importJson).flatMap(result -> feedback(exchange, result, false));
    }

    @PostMapping(StorePaths.CATALOG + "/demo")
    public Mono<RedirectView> demo(ServerWebExchange exchange) {
        return importer.importJson(demo).flatMap(result -> feedback(exchange, result, true));
    }

    private Mono<RedirectView> feedback(ServerWebExchange exchange, ImportResult result, boolean example) {
        return exchange.getSession().map(session -> {
            session.getAttributes().put("notice", (example ? "Каталог мастерской готов. " : "")
                    + "Добавлено: " + result.added() + ". Обновлено: " + result.updated() + ".");
            return StoreRedirects.to("/items");
        });
    }
}
