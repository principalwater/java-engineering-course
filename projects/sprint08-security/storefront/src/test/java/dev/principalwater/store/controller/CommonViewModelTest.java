package dev.principalwater.store.controller;

import dev.principalwater.store.ReactiveTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommonViewModelTest extends ReactiveTestBase {
    @Test
    void noticeMutationWaitsForCsrfRotationAndItsSessionSave() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/account/login"));
        var session = exchange.getSession().block(TIMEOUT);
        session.getAttributes().put("notice", "Каталог готов");
        var rotation = Sinks.<CsrfToken>one();
        exchange.getAttributes().put(CsrfToken.class.getName(), rotation.asMono());

        // Реальный Redis выявил гонку delta: уведомление нельзя удалять, пока CSRF сохраняет смену id.
        StepVerifier.create(new CommonViewModel(null).notice(exchange))
                .expectSubscription()
                .then(() -> assertEquals("Каталог готов", session.getAttribute("notice")))
                .then(() -> rotation.tryEmitValue(new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "new-token")))
                .expectNext("Каталог готов").verifyComplete();
        assertFalse(session.getAttributes().containsKey("notice"));
    }
}
