package dev.principalwater.bank.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Включается только развёртыванием (BANK_OBSERVABILITY_METRICS_SERVER_ENABLED=true): по умолчанию
// worker не открывает сетевых портов, а тесты и replay-запуски не занимают порт.
@ConfigurationProperties("bank.observability.metrics-server")
public record MetricsServerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0.0.0.0") String host,
        @DefaultValue("8080") int port) {}
