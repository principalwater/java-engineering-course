package dev.principalwater.study.mvc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {
    @GetMapping(value = "/home", produces = "text/html;charset=UTF-8")
    public String home() {
        return "<h1>Hello, world!</h1>";
    }
}
