package dev.principalwater.store.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    public SecurityFilterChain storeSecurity(HttpSecurity http) throws Exception {
        // Магазин анонимный, но изменяющие HTML-формы используют штатную защиту CSRF.
        return http.authorizeHttpRequests(access -> access.anyRequest().permitAll()).build();
    }
}
