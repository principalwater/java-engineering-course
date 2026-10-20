package dev.principalwater.bank.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

// HTTP-сервисы отдают метрики через Actuator; scrape-сервер нужен только non-web worker.
@AutoConfiguration(
        afterName =
                "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration")
@ConditionalOnNotWebApplication
@ConditionalOnClass(PrometheusMeterRegistry.class)
@ConditionalOnProperty(
        prefix = "bank.observability.metrics-server",
        name = "enabled",
        havingValue = "true")
@ConditionalOnBean(PrometheusMeterRegistry.class)
@EnableConfigurationProperties(MetricsServerProperties.class)
public class MetricsServerAutoConfiguration {
    // destroyMethod закрывает порт и потоки вместе с контекстом, в том числе при сбое запуска.
    @Bean(initMethod = "start", destroyMethod = "stop")
    PrometheusScrapeServer prometheusScrapeServer(
            PrometheusMeterRegistry registry, MetricsServerProperties properties) {
        return new PrometheusScrapeServer(registry, properties.host(), properties.port());
    }
}
