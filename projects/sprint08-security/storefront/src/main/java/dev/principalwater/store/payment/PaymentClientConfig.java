package dev.principalwater.store.payment;

import dev.principalwater.store.payment.generated.api.PaymentsApi;
import dev.principalwater.store.payment.generated.client.ApiClient;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentClientConfig {
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider paymentsConnections() {
        return ConnectionProvider.builder("payments").maxConnections(8).pendingAcquireMaxCount(32)
                .pendingAcquireTimeout(Duration.ofSeconds(1)).maxIdleTime(Duration.ofSeconds(20)).build();
    }
    @Bean
    public PaymentsApi paymentsApi(PaymentProperties properties, WebClient.Builder builder,
                                    ConnectionProvider paymentsConnections, LoopResources storeHttpLoops) {
        var transport = HttpClient.create(paymentsConnections).runOn(storeHttpLoops)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(Duration.ofSeconds(3));
        var web = builder.clone().clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024)).build();
        var client = new ApiClient(web).setBasePath(properties.url().toString());
        // Config tree читает смонтированный secret; для production предпочтителен внешний Vault и TLS/mTLS.
        if (!properties.serviceToken().isBlank()) { client.setBearerToken(properties.serviceToken()); }
        return new PaymentsApi(client);
    }
}
