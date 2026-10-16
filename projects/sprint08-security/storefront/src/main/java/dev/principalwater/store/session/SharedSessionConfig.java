package dev.principalwater.store.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.model.StoreUserDetails;
import dev.principalwater.store.runtime.RuntimeProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.session.data.redis.config.annotation.SpringSessionRedisConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.server.RedisWebSessionConfiguration;
import reactor.core.publisher.Mono;

/**
 * Общие сессии на отдельном Redis через штатный ReactiveRedisSessionRepository (профиль shared-sessions).
 *
 * <p>Фабрика соединений намеренно не кандидат для обычного внедрения (defaultCandidate = false): иначе Boot решил бы,
 * что фабрика уже есть, и не создал бы публичный кэш каталога, а сессии могли бы уйти в кэшевый Redis. Spring Session
 * берёт её только по своему квалификатору. Лишь профиль задаёт хранилище: без него остаётся локальная WebSession.
 */
@Configuration(proxyBeanMethods = false)
@Profile("shared-sessions")
@EnableConfigurationProperties(SessionStoreProperties.class)
@Import(RedisWebSessionConfiguration.class)
public class SharedSessionConfig {
    @Bean(defaultCandidate = false)
    @SpringSessionRedisConnectionFactory
    public LettuceConnectionFactory sessionConnectionFactory(SessionStoreProperties properties, ClientResources redisResources,
                                                              RuntimeProperties budget) {
        var server = new RedisStandaloneConfiguration(properties.host(), properties.port());
        if (!properties.username().isEmpty()) {
            server.setUsername(properties.username());
        }
        server.setPassword(RedisPassword.of(properties.password()));
        // Недоступное хранилище отвечает отказом в пределах таймаутов: команды не копятся в очереди и не ждут вечно.
        var client = LettuceClientConfiguration.builder().clientResources(redisResources)
                .commandTimeout(properties.commandTimeout())
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .requestQueueSize(budget.redisQueue())
                        .timeoutOptions(TimeoutOptions.enabled(properties.commandTimeout()))
                        .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build()).build())
                .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean("springSessionDefaultRedisSerializer")
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return sessionSerializer(SharedSessionConfig.class.getClassLoader());
    }

    @Bean
    public ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository> sessionRepositoryOptions(
            SessionStoreProperties properties, ServerProperties server) {
        return repository -> {
            repository.setRedisKeyNamespace(properties.namespace());
            // Тот же срок простоя, что у локальной WebSession: профиль не меняет время жизни сессии.
            repository.setDefaultMaxInactiveInterval(server.getReactive().getSession().getTimeout());
        };
    }

    // PING входит в минимальный ACL сессионного пользователя; INFO из стандартного индикатора Boot ему недоступен.
    @Bean
    public ReactiveHealthIndicator sessionStoreHealthIndicator(
            @SpringSessionRedisConnectionFactory ReactiveRedisConnectionFactory sessions, SessionStoreProperties properties) {
        return () -> Mono.usingWhen(Mono.fromSupplier(sessions::getReactiveConnection), ReactiveRedisConnection::ping,
                        ReactiveRedisConnection::closeLater)
                .timeout(properties.commandTimeout()).map(reply -> Health.up().build())
                .onErrorResume(failure -> Mono.just(Health.down().build()));
    }

    /**
     * JSON вместо JDK-сериализации. Allowlist Spring Security пропускает только известные классы контекста
     * безопасности и классы с явным mixin; произвольный @class из Redis отвергается при чтении.
     */
    public static RedisSerializer<Object> sessionSerializer(ClassLoader loader) {
        var mapper = new ObjectMapper();
        mapper.registerModules(SecurityJackson2Modules.getModules(loader));
        mapper.addMixIn(StoreUserDetails.class, StoreUserDetailsMixin.class);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
