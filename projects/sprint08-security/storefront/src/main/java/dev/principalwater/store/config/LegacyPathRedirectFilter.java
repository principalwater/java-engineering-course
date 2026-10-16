package dev.principalwater.store.config;

import dev.principalwater.store.controller.StorePaths;
import java.net.URI;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Старые адреса личных страниц остаются рабочими закладками: безопасный GET перенаправляется на новый адрес.
 *
 * <p>Фильтр стоит после ограничения нагрузки (-200) и до security (-100), поэтому правила доступа знают только
 * новые адреса. Небезопасные методы не переписываются, а цель строится из константы и хвоста пути, без параметров.
 */
@Component
@Order(-150)
public class LegacyPathRedirectFilter implements WebFilter {
    private static final Map<String, String> MOVED = Map.of(
            "/profile", StorePaths.CABINET,
            "/analytics", StorePaths.CABINET_ANALYTICS,
            "/account/seller", StorePaths.STORE);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.GET && request.getMethod() != HttpMethod.HEAD) {
            return chain.filter(exchange);
        }
        String path = request.getPath().pathWithinApplication().value();
        for (var moved : MOVED.entrySet()) {
            if (path.equals(moved.getKey()) || path.startsWith(moved.getKey() + "/")) {
                String query = request.getURI().getRawQuery();
                try {
                    var target = URI.create(moved.getValue() + path.substring(moved.getKey().length())
                            + (query == null ? "" : "?" + query));
                    // Временный редирект: закладка обновляется, а браузер не запоминает адрес навсегда.
                    exchange.getResponse().setStatusCode(HttpStatus.TEMPORARY_REDIRECT);
                    exchange.getResponse().getHeaders().setLocation(target);
                    return exchange.getResponse().setComplete();
                } catch (IllegalArgumentException malformed) {
                    return chain.filter(exchange);
                }
            }
        }
        return chain.filter(exchange);
    }
}
