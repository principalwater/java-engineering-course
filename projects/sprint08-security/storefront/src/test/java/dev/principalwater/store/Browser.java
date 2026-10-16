package dev.principalwater.store;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Браузер со своими cookie и CSRF-токеном; клиент можно сменить, чтобы тот же вход обслужила другая реплика. */
public final class Browser {
    private static final Pattern CSRF = Pattern.compile("<input\\b(?=[^>]*\\bname=\"_csrf\")[^>]*\\bvalue=\"([^\"]+)\"");
    private static final Pattern CHECKOUT_KEY = Pattern.compile(
            "<input\\b(?=[^>]*\\bname=\"checkoutKey\")[^>]*\\bvalue=\"([^\"]+)\"");
    private static final Pattern AMOUNT_MINOR = Pattern.compile(
            "<input\\b(?=[^>]*\\bname=\"amountMinor\")[^>]*\\bvalue=\"([^\"]+)\"");
    public final Map<String, String> cookies = new HashMap<>();
    public String csrf;
    public HttpHeaders headers = HttpHeaders.EMPTY;
    private WebTestClient web;
    private String checkoutKey;
    private String amountMinor;

    public Browser(WebTestClient web) {
        this.web = web;
    }

    public Browser using(WebTestClient other) {
        web = other;
        return this;
    }

    public String get(String path, HttpStatus status) {
        var result = web.get().uri(path).accept(MediaType.TEXT_HTML)
                .cookies(values -> cookies.forEach(values::set)).exchange().expectStatus().isEqualTo(status)
                .expectBody(String.class).returnResult();
        saveCookies(result);
        String html = Objects.requireNonNullElse(result.getResponseBody(), "");
        var token = CSRF.matcher(html);
        if (token.find()) {
            csrf = token.group(1);
        }
        var key = CHECKOUT_KEY.matcher(html);
        if (key.find()) {
            checkoutKey = key.group(1);
        }
        var amount = AMOUNT_MINOR.matcher(html);
        if (amount.find()) {
            amountMinor = amount.group(1);
        }
        return html;
    }

    public EntityExchangeResult<String> post(String path, Map<String, String> values, boolean withCsrf, HttpStatus status) {
        if (path.equals("/buy") && !values.containsKey("checkoutKey") && (checkoutKey == null || amountMinor == null)) {
            get("/cart/items", HttpStatus.OK);
        }
        var form = new LinkedMultiValueMap<String, String>();
        values.forEach(form::set);
        if (path.equals("/buy") && !values.containsKey("checkoutKey")) {
            assertNotNull(checkoutKey, "The rendered checkout must supply its stable payment key");
            form.set("checkoutKey", checkoutKey);
        }
        if (path.equals("/buy") && !values.containsKey("amountMinor")) {
            assertNotNull(amountMinor, "The rendered checkout must supply its visible amount");
            form.set("amountMinor", amountMinor);
        }
        if (withCsrf) {
            form.set("_csrf", token());
        }
        var result = web.post().uri(path).accept(MediaType.TEXT_HTML).cookies(request -> cookies.forEach(request::set))
                .body(BodyInserters.fromFormData(form)).exchange().expectStatus().isEqualTo(status)
                .expectBody(String.class).returnResult();
        saveCookies(result);
        if (status.is2xxSuccessful() || status.is3xxRedirection()) {
            if (path.equals("/cart/items") || path.equals("/account/login") || path.equals("/account/logout")) {
                checkoutKey = null;
                amountMinor = null;
            }
        }
        return result;
    }

    public void upload(String catalog, HttpStatus status) {
        var form = new MultipartBodyBuilder();
        form.part("catalog", catalog.getBytes(StandardCharsets.UTF_8)).filename("catalog.json")
                .contentType(MediaType.APPLICATION_JSON);
        form.part("_csrf", token());
        var result = web.post().uri("/catalog/import").accept(MediaType.TEXT_HTML)
                .cookies(request -> cookies.forEach(request::set)).body(BodyInserters.fromMultipartData(form.build()))
                .exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult();
        saveCookies(result);
    }

    // Подтверждение выхода содержит форму для любой роли, поэтому даёт CSRF-токен текущей сессии.
    private String token() {
        if (csrf == null) {
            get("/account/logout", HttpStatus.OK);
        }
        assertNotNull(csrf, "A rendered form must supply the CSRF token");
        return csrf;
    }

    private void saveCookies(EntityExchangeResult<?> result) {
        headers = result.getResponseHeaders();
        result.getResponseCookies().forEach((name, values) -> values.forEach(cookie -> {
            assertTrue(cookie.isHttpOnly(), "Identity cookies must be HttpOnly");
            if (cookie.getMaxAge().isZero()) {
                cookies.remove(name);
            } else {
                cookies.put(name, cookie.getValue());
            }
        }));
    }
}
