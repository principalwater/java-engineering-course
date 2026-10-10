package dev.principalwater.study.pair.frontend;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

@Controller
public class PageController {
    private final BackendMessageClient backend;
    public PageController(BackendMessageClient backend) {
        this.backend = backend;
    }
    @GetMapping("/")
    String home() { return "home"; }
    @GetMapping("/login")
    String login() { return "login"; }
    @GetMapping("/message")
    Mono<String> message(Principal principal, Model model) {
        model.addAttribute("username", principal.getName());
        return backend.message().doOnNext(message -> model.addAttribute("message", message)).thenReturn("message");
    }
}
