package dev.principalwater.bank.front.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/")
    public String index() {
        return "redirect:/cabinet";
    }

    @GetMapping({"/cabinet", "/account"})
    public ResponseEntity<Resource> cabinet() {
        return page("cabinet");
    }

    @GetMapping("/login")
    public ResponseEntity<Resource> login() {
        return page("login");
    }

    private ResponseEntity<Resource> page(String name) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/" + name + ".html"));
    }
}
