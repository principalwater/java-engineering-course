package dev.principalwater.study.pair.backend;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class MessageController {
    @GetMapping("/api/message")
    @PreAuthorize("hasAuthority('SERVICE')")
    public Mono<String> message() {
        return Mono.just("Hello World!");
    }
}
