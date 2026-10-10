package dev.principalwater.study.redis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.principalwater.study.redis.model.MessageNotification;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisKeyValueAdapter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import redis.embedded.RedisServer;

@SpringJUnitConfig(MessageNotificationRepositoryTest.RedisFixture.class)
class MessageNotificationRepositoryTest {
    @Autowired
    private MessageNotificationRepository notifications;
    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Test
    void receiverIndexFindsItsMessageAndBecomesEmptyAfterExpiration() {
        try (var connection = connectionFactory.getConnection()) {
            System.out.println("Embedded Redis version: " + connection.serverCommands().info("server").getProperty("redis_version"));
        }
        var outbound = new MessageNotification("Рагнар", "Лагерта", "Го в Англию?");
        var inbound = new MessageNotification("Лагерта", "Рагнар", "Лол. Го.");
        notifications.save(outbound);
        notifications.save(inbound);
        assertThat(notifications.findAllByReceiver("Лагерта")).containsExactly(outbound);
        assertThat(notifications.findAllByReceiver("Рагнар")).containsExactly(inbound);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notifications.findAllByReceiver("Лагерта")).isEmpty());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRedisRepositories(basePackageClasses = MessageNotificationRepository.class,
            enableKeyspaceEvents = RedisKeyValueAdapter.EnableKeyspaceEvents.ON_STARTUP)
    static class RedisFixture {
        @Bean(initMethod = "start", destroyMethod = "stop")
        RedisServer redisServer() throws IOException {
            int port;
            try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            // Свободный порт не резервируется до запуска процесса; ошибка запуска завершает тест.
            return RedisServer.newRedisServer().port(port).setting("bind 127.0.0.1")
                    .setting("save \"\"").setting("appendonly no").build();
        }

        @Bean
        LettuceConnectionFactory redisConnectionFactory(RedisServer server) {
            return new LettuceConnectionFactory("127.0.0.1", server.ports().get(0));
        }

        @Bean
        RedisTemplate<Object, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
            var template = new RedisTemplate<Object, Object>();
            template.setConnectionFactory(connectionFactory);
            return template;
        }
    }
}
