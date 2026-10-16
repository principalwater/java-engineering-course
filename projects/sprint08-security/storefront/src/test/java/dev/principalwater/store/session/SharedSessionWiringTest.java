package dev.principalwater.store.session;

import dev.principalwater.store.ReactiveTestBase;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.model.StoreUserDetails;
import dev.principalwater.store.runtime.RuntimeProperties;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.web.server.session.WebSessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSessionWiringTest extends ReactiveTestBase {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class))
            .withUserConfiguration(RuntimeFixture.class, SharedSessionConfig.class)
            .withPropertyValues("spring.data.redis.host=cache.example.test", "spring.data.redis.port=6380",
                    "store.sessions.host=sessions.example.test", "store.sessions.port=6390", "store.sessions.username=sessions",
                    "store.sessions.password=test-only-password", "store.sessions.namespace=store:session:wiring",
                    "server.reactive.session.timeout=7m");

    @Test
    void sessionsUseTheirOwnRedisWhileThePublicCacheFactoryStaysTheOnlyDefault() {
        runner.withInitializer(context -> context.getEnvironment().addActiveProfile("shared-sessions")).run(context -> {
            // Без квалификатора внедряется только кэшевая фабрика Boot: сессионная не отключила её автонастройку.
            var cache = assertInstanceOf(LettuceConnectionFactory.class, context.getBean(ReactiveRedisConnectionFactory.class));
            assertEquals("cache.example.test", cache.getHostName());
            assertEquals(6380, cache.getPort());

            var repository = context.getBean(ReactiveRedisSessionRepository.class);
            var template = assertInstanceOf(ReactiveRedisTemplate.class, repository.getSessionRedisOperations());
            var sessions = assertInstanceOf(LettuceConnectionFactory.class, template.getConnectionFactory());
            assertNotSame(cache, sessions);
            assertEquals("sessions.example.test", sessions.getHostName());
            assertEquals(6390, sessions.getPort());
            assertEquals("sessions", sessions.getStandaloneConfiguration().getUsername());
            assertInstanceOf(WebSessionManager.class, context.getBean("webSessionManager"));
            // Срок простоя берётся из существующей настройки сессии, а не из отдельного значения профиля.
            Session created = repository.createSession().block(TIMEOUT);
            assertEquals(Duration.ofMinutes(7), created.getMaxInactiveInterval());

            // Значение сессии записывается JSON-ом, а не потоком JDK-сериализации (заголовок 0xACED).
            var principal = new StoreUserDetails(new StoreUser(UUID.randomUUID(), UUID.randomUUID(), "wiring.user", "hash",
                    "Wiring", AccountRole.BUYER, null));
            var written = template.getSerializationContext().getHashValueSerializationPair().write(new SecurityContextImpl(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities())));
            assertNotNull(written);
            byte[] bytes = new byte[written.remaining()];
            written.get(bytes);
            assertEquals('{', bytes[0]);
            assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("\"@class\""));
        });
    }

    @Test
    void withoutTheProfileNothingStoresSessionsInRedis() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(ReactiveSessionRepository.class).isEmpty());
            assertTrue(context.getBeansOfType(SessionStoreProperties.class).isEmpty());
            assertEquals("cache.example.test",
                    assertInstanceOf(LettuceConnectionFactory.class, context.getBean(ReactiveRedisConnectionFactory.class)).getHostName());
        });
    }

    @ParameterizedTest
    @MethodSource("incompleteStores")
    void theProfileRefusesToStartWithoutACompleteAuthenticatedBoundedStore(String property, String value) {
        runner.withInitializer(context -> context.getEnvironment().addActiveProfile("shared-sessions"))
                .withPropertyValues(property + "=" + value).run(context -> {
                    assertNotNull(context.getStartupFailure(), "An incomplete session store must fail the startup");
                    assertTrue(rootMessage(context.getStartupFailure()).contains("Session"));
                });
    }

    static Stream<Arguments> incompleteStores() {
        return Stream.of(Arguments.of("store.sessions.host", " "), Arguments.of("store.sessions.password", ""),
                Arguments.of("store.sessions.port", "0"), Arguments.of("store.sessions.namespace", "bad namespace"),
                Arguments.of("store.sessions.connect-timeout", "0s"), Arguments.of("store.sessions.command-timeout", "30s"));
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    // Минимум окружения приложения, который SharedSessionConfig берёт у RuntimeConfig и Boot. Не @Configuration: такой
    // класс попал бы в сканирование приложения, которое другие тесты запускают программно.
    @EnableConfigurationProperties({ServerProperties.class, RuntimeProperties.class})
    static class RuntimeFixture {
        @Bean(destroyMethod = "shutdown")
        ClientResources redisResources() {
            return DefaultClientResources.create();
        }
    }
}
