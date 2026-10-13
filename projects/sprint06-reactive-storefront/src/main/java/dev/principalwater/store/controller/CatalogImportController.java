package dev.principalwater.store.controller;

import dev.principalwater.store.service.CatalogImportService;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class CatalogImportController {
    private final CatalogImportService importer;

    public CatalogImportController(CatalogImportService importer) {
        this.importer = importer;
    }

    @GetMapping("/catalog")
    public String catalog() {
        return "catalog";
    }

    @PostMapping("/catalog/import")
    public RedirectView upload(@RequestParam("catalog") MultipartFile file, RedirectAttributes feedback) throws IOException {
        var result = importer.importJson(file.getBytes());
        feedback.addFlashAttribute("notice", "Добавлено: " + result.added() + ". Обновлено: " + result.updated() + ".");
        return StoreRedirects.to("/items");
    }

    @PostMapping("/catalog/demo")
    public RedirectView demo(RedirectAttributes feedback) throws IOException {
        var result = importer.importJson(new ClassPathResource("static/catalog-demo.json").getContentAsByteArray());
        feedback.addFlashAttribute("notice", "Каталог мастерской готов. Добавлено: " + result.added()
                + ". Обновлено: " + result.updated() + ".");
        return StoreRedirects.to("/items");
    }
}
