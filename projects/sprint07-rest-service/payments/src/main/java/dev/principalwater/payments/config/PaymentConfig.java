package dev.principalwater.payments.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.r2dbc.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.netty.resources.LoopResources;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {
    private static final int TRANSACTION_TIMEOUT_SECONDS = 3;

    @Bean
    public TransactionalOperator paymentTransactions(ConnectionFactory connections) {
        var definition = new DefaultTransactionDefinition();
        definition.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return TransactionalOperator.create(new R2dbcTransactionManager(connections), definition);
    }

    @Bean
    public ConnectionFactoryOptionsBuilderCustomizer paymentDatabaseTimeouts() {
        return builder -> {
            var options = builder.build();
            if ("postgresql".equals(options.getValue(ConnectionFactoryOptions.DRIVER))) {
                builder.option(Option.valueOf("options"), Map.of("lock_timeout", "2000ms", "statement_timeout", "3000ms"));
            }
        };
    }

    @Bean
    public ReactorResourceFactory paymentHttpResources(PaymentProperties properties) {
        var resources = new ReactorResourceFactory();
        resources.setUseGlobalResources(false);
        resources.setLoopResourcesSupplier(() -> LoopResources.create("payments-http", properties.getIoWorkers(), true));
        resources.setShutdownQuietPeriod(Duration.ZERO);
        resources.setShutdownTimeout(Duration.ofSeconds(5));
        return resources;
    }
}
