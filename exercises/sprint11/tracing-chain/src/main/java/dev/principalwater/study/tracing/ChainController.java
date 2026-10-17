package dev.principalwater.study.tracing;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
class ChainController {
    private static final Logger LOG = LoggerFactory.getLogger(ChainController.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private final RestClient nextService;

    ChainController(RestClient.Builder builder, ServerProperties server) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        // RestClient.create() обходит настроенный Boot builder и разрывает дерево spans.
        nextService = builder.requestFactory(factory)
                .baseUrl("http://127.0.0.1:" + (server.getPort() + 1)).build();
    }

    @GetMapping("/chain")
    ResponseEntity<String> chain() {
        LOG.info("Forwarding chain request");
        try {
            return nextService.get().uri("/chain").retrieve().toEntity(String.class);
        } catch (RestClientException failure) {
            // Ошибка остаётся видимой в HTTP/span, без раскрытия тела ответа соседнего сервиса.
            LOG.warn("Chain dependency unavailable");
            return ResponseEntity.internalServerError().body("Chain dependency unavailable");
        }
    }
}
