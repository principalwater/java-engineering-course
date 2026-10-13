package dev.principalwater.store.controller;

import dev.principalwater.store.config.CartProperties;
import dev.principalwater.store.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

@Component
public class CartIdentityInterceptor implements HandlerInterceptor {
    public static final String ATTRIBUTE_NAME = "cartId";
    private static final int UUID_TEXT_LENGTH = 36;
    private static final Duration COOKIE_LIFETIME = Duration.ofDays(30);
    private final CartService carts;
    private final CartProperties properties;

    public CartIdentityInterceptor(CartService carts, CartProperties properties) {
        this.carts = carts;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var cookie = WebUtils.getCookie(request, properties.cookieName());
        UUID previous = cookie == null ? null : parse(cookie.getValue());
        UUID id = carts.identify(previous);
        request.setAttribute(ATTRIBUTE_NAME, id);
        if (!id.equals(previous)) {
            // Cookie — непрозрачная capability корзины; UUID не берётся из пользовательского URL.
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.cookieName(), id.toString())
                    .path("/").httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                    .maxAge(COOKIE_LIFETIME).build().toString());
        }
        return true;
    }

    private UUID parse(String value) {
        if (value == null || value.length() != UUID_TEXT_LENGTH) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
