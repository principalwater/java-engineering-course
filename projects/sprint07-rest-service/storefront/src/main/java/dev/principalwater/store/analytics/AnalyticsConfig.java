package dev.principalwater.store.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.scheduler.Scheduler;
import reactor.netty.resources.LoopResources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "store.analytics.enabled", havingValue = "true")
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfig {
    @Bean(destroyMethod = "close")
    public ClickHouseClient clickHouseClient(AnalyticsProperties properties, ObjectMapper json, WebClient.Builder builder,
                                              @Qualifier("cacheCodecScheduler") Scheduler codec, LoopResources storeHttpLoops) {
        return new ClickHouseClient(properties, json, builder, codec, storeHttpLoops);
    }
}
