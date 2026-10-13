package dev.principalwater.store.config;

import dev.principalwater.store.controller.CartIdentityInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DisplayProperties.class, CartProperties.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebConfig implements WebMvcConfigurer {
    private final CartIdentityInterceptor identity;

    public WebConfig(CartIdentityInterceptor identity) {
        this.identity = identity;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identity).addPathPatterns("/", "/items", "/items/**", "/cart", "/cart/**",
                "/orders", "/orders/**", "/buy", "/catalog", "/catalog/**", "/analytics", "/analytics/**");
    }
}
