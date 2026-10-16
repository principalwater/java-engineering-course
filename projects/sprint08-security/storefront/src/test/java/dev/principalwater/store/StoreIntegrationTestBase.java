package dev.principalwater.store;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
public abstract class StoreIntegrationTestBase extends DatabaseTestBase {
    @DynamicPropertySource
    static void payments(DynamicPropertyRegistry properties) {
        properties.add("store.payments.url", () -> "http://127.0.0.1:" + PaymentsStub.SERVER.port());
        properties.add("spring.security.oauth2.client.provider.payments.token-uri",
                () -> "http://127.0.0.1:" + PaymentsStub.SERVER.port() + "/oauth/token");
        // Восстановление включено во всём общем контексте: настоящий JavaMailSender пишет в SMTP-заглушку.
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> SmtpStub.PORT);
        properties.add("store.recovery.public-base-url", () -> "https://shop.example.test");
        properties.add("store.recovery.sender", () -> "no-reply@example.test");
    }
    protected final reactor.core.publisher.Mono<Long> buy(java.util.UUID cartId) {
        var checkout = context.getBean(dev.principalwater.store.service.CheckoutService.class);
        return checkout.availability(cartId).flatMap(view -> checkout.buy(cartId, java.util.UUID.fromString(view.checkoutKey()), Long.parseLong(view.requiredMinor())));
    }
    @BeforeEach
    protected final void resetPayments() { PaymentsStub.reset(); SmtpStub.reset(); }
}
