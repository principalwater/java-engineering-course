package dev.principalwater.store.runtime;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.boot.autoconfigure.r2dbc.ConnectionFactoryOptionsBuilderCustomizer;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.util.Map;
import reactor.netty.resources.ConnectionProvider;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.LoopResources;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeProperties.class)
@EnableScheduling
public class RuntimeConfig {
    @Bean(destroyMethod = "dispose")
    public Scheduler cacheCodecScheduler(RuntimeProperties budget) {
        return Schedulers.newBoundedElastic(budget.codecWorkers(), budget.codecQueue(), "store-codec");
    }
    @Bean
    public ReactorResourceFactory storeHttpResources(RuntimeProperties budget) {
        var resources = new ReactorResourceFactory();
        resources.setUseGlobalResources(false);
        resources.setLoopResourcesSupplier(() -> LoopResources.create("store-http", budget.ioWorkers(), true));
        resources.setConnectionProviderSupplier(() -> ConnectionProvider.builder("store-http-default")
                .maxConnections(16).pendingAcquireMaxCount(32).pendingAcquireTimeout(Duration.ofSeconds(1)).build());
        resources.setShutdownQuietPeriod(Duration.ZERO);
        resources.setShutdownTimeout(Duration.ofSeconds(5));
        return resources;
    }
    @Bean(destroyMethod = "")
    public LoopResources storeHttpLoops(ReactorResourceFactory storeHttpResources) {
        return storeHttpResources.getLoopResources();
    }
    @Bean
    public ConnectionFactoryOptionsBuilderCustomizer databaseBudget(LoopResources storeHttpLoops) {
        return builder -> {
            if ("postgresql".equals(builder.build().getValue(ConnectionFactoryOptions.DRIVER))) {
                builder.option(Option.valueOf("loopResources"), storeHttpLoops)
                        .option(Option.valueOf("options"), Map.of("lock_timeout", "2000ms", "statement_timeout", "3000ms"));
            }
        };
    }
    @Bean(destroyMethod = "shutdown")
    public ClientResources redisResources() {
        return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
    }
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisBudget(RuntimeProperties budget, ClientResources redisResources) {
        // При недоступном кэше очередь клиента не должна расти до Integer.MAX_VALUE.
        return builder -> builder.clientResources(redisResources).clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(budget.redisQueue())
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(250)).build()).build());
    }
}
