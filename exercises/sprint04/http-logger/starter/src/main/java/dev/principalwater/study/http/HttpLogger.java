package dev.principalwater.study.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.filter.OncePerRequestFilter;

public class HttpLogger extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(HttpLogger.class);
    private final Level level;

    public HttpLogger(Level level) { this.level = level; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // Query, заголовки и тело могут содержать секреты: записываем только метод и путь.
        String path = request.getRequestURI().replace('\r', '_').replace('\n', '_');
        LOG.atLevel(level).log("Получен {} запрос {}", request.getMethod(), path);
        chain.doFilter(request, response);
    }
}
