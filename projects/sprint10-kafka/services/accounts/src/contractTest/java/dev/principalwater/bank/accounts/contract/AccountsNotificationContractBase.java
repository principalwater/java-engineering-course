package dev.principalwater.bank.accounts.contract;

import dev.principalwater.bank.accounts.service.ProfileService;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.contract.ProfileRequest;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.testing.BankFixtures;
import dev.principalwater.bank.testing.KafkaContractConfiguration;
import dev.principalwater.bank.testing.KafkaFixture;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@Import(KafkaContractConfiguration.class)
@TestPropertySource(properties = "spring.kafka.security.protocol=PLAINTEXT")
public abstract class AccountsNotificationContractBase extends AccountsContractBase {
    @Autowired private ProfileService profiles;
    @Autowired private KafkaOutbox outbox;

    static {
        KafkaFixture.topics(NotificationSource.ACCOUNTS.getTopic());
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KafkaFixture::bootstrapServers);
    }

    @BeforeEach
    void clearMessages() {
        KafkaFixture.clear(NotificationSource.ACCOUNTS.getTopic());
    }

    public void publishNotification() {
        profiles.update(
                BankFixtures.actor("alice"),
                new ProfileRequest("Alice Example", LocalDate.of(1990, 6, 15), 1));
        outbox.pendingIds().stream()
                .filter(
                        id ->
                                Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                "SELECT topic=? FROM bank_kafka_outbox WHERE id=?",
                                                Boolean.class,
                                                NotificationSource.ACCOUNTS.getTopic(),
                                                id)))
                .forEach(outbox::recover);
    }
}
