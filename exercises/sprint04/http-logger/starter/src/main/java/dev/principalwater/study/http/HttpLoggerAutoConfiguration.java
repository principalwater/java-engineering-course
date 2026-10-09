package dev.principalwater.study.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(HttpLoggerProperties.class)
@ConditionalOnProperty(prefix = "application.http.logging", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class HttpLoggerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public HttpLogger httpLogger(HttpLoggerProperties properties) {
        return new HttpLogger(properties.getLevel());
    }
}
