package dev.principalwater.store.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.principalwater.store.TestBase;
import dev.principalwater.store.model.AccountRole;
import dev.principalwater.store.model.StoreUser;
import dev.principalwater.store.model.StoreUserDetails;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionJsonTest extends TestBase {
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz0123";
    private final RedisSerializer<Object> serializer = SharedSessionConfig.sessionSerializer(getClass().getClassLoader());

    @Test
    void theSecurityContextSurvivesRedisAsJsonWithoutTheHashOrAnyJavaSerialization() {
        // Хеш намеренно не стирается: сериализатор сам обязан его не писать, а не рассчитывать на eraseCredentials.
        var principal = new StoreUserDetails(new StoreUser(UUID.randomUUID(), UUID.randomUUID(), "json.user", PASSWORD_HASH,
                "Json", AccountRole.SELLER, "json@example.test", 7, 0L));
        var stored = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));

        byte[] bytes = serializer.serialize(stored);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{"), "JDK serialization must not be used");
        assertFalse(json.contains(PASSWORD_HASH) || json.contains("passwordHash") || json.contains("json@example.test"));
        var restored = (SecurityContext) serializer.deserialize(bytes);
        var account = (StoreUserDetails) restored.getAuthentication().getPrincipal();
        assertEquals(principal.accountId(), account.accountId());
        assertEquals(principal.cartId(), account.cartId());
        assertEquals("json.user", account.getUsername());
        assertEquals("Json", account.getDisplayName());
        assertEquals(AccountRole.SELLER, account.getRole());
        assertEquals(7, account.authVersion());
        assertNull(account.getPassword());
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_SELLER")),
                new ArrayList<>(restored.getAuthentication().getAuthorities()));
        assertTrue(restored.getAuthentication().isAuthenticated());
    }

    @Test
    void theCsrfTokenAndTheNoticeOfTheSessionSurviveRedisUnchanged() {
        // Именно эти значения WebFlux Security и приложение кладут в сессию помимо контекста безопасности.
        var csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "csrf-token-value");
        var restored = (DefaultCsrfToken) serializer.deserialize(serializer.serialize(csrf));
        assertEquals(csrf.getHeaderName(), restored.getHeaderName());
        assertEquals(csrf.getParameterName(), restored.getParameterName());
        assertEquals(csrf.getToken(), restored.getToken());
        assertEquals("Каталог готов", serializer.deserialize(serializer.serialize("Каталог готов")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"java.lang.ProcessBuilder", "javax.naming.InitialContext", "org.springframework.context.support.ClassPathXmlApplicationContext"})
    void aForeignClassNamedInRedisIsRejectedInsteadOfInstantiated(String type) {
        byte[] hostile = ("{\"@class\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThrows(SerializationException.class, () -> serializer.deserialize(hostile));
    }

    @Test
    void aFinalTypeIgnoringUnknownFieldsStillNeedsExplicitPermission() {
        byte[] hostile = ("{\"@class\":\"" + ForeignValue.class.getName() + "\",\"marker\":\"foreign\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(SerializationException.class, () -> serializer.deserialize(hostile));
    }

    // Условный final DTO зависимости: отсутствие ошибок конструктора не должно разрешать чужой тип из Redis.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ForeignValue {
        public String marker;
    }

    @Test
    void aJavaSerializedPayloadIsRejected() throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(buffer)) {
            stream.writeObject(new ArrayList<>(List.of("payload")));
        }
        assertThrows(SerializationException.class, () -> serializer.deserialize(buffer.toByteArray()));
    }
}
