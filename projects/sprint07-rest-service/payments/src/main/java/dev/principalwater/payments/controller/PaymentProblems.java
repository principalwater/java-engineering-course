package dev.principalwater.payments.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class PaymentProblems {
    private PaymentProblems() {
    }

    public static ProblemDetail body(HttpStatus status, String detail, ServerWebExchange exchange) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        return problem;
    }

    public static Mono<Void> write(HttpStatus status, String detail, ServerWebExchange exchange, ObjectMapper json) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return Mono.fromCallable(() -> json.writeValueAsBytes(body(status, detail, exchange)))
                .flatMap(bytes -> exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))));
    }
}
