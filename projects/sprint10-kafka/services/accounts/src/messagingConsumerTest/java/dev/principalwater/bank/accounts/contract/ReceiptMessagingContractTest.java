package dev.principalwater.bank.accounts.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.principalwater.bank.accounts.AccountsTestBase;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.testing.KafkaContractConfiguration;
import dev.principalwater.bank.testing.KafkaFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@Import(KafkaContractConfiguration.class)
@TestPropertySource(
        properties = {"spring.kafka.security.protocol=PLAINTEXT", "stubrunner.cloud.enabled=false"})
@AutoConfigureStubRunner(
        ids = "dev.principalwater.bank:notifications:1.0.0:stubs",
        stubsMode = StubRunnerProperties.StubsMode.CLASSPATH)
class ReceiptMessagingContractTest extends AccountsTestBase {
    @Autowired private StubFinder stubs;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    static {
        KafkaFixture.topics(
                BankTopics.NOTIFICATION_RECEIPTS,
                BankTopics.NOTIFICATION_RECEIPTS + BankTopics.DLT_SUFFIX);
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KafkaFixture::bootstrapServers);
    }

    @AfterEach
    void stop() {
        listeners.getListenerContainers().forEach(container -> container.stop());
    }

    @Test
    void productionReceiptListenerConsumesVerifiedEnvelopeAndDeduplicatesStableSource() {
        KafkaFixture.clear(BankTopics.NOTIFICATION_RECEIPTS);
        listeners.getListenerContainers().forEach(container -> container.start());
        assertThat(stubs.trigger("notifications_notification")).isTrue();
        assertThat(stubs.trigger("notifications_notification")).isTrue();
        long end = KafkaFixture.endOffset(BankTopics.NOTIFICATION_RECEIPTS);
        await().atMost(KafkaFixture.WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                KafkaFixture.committed(
                                                        BankTopics.ACCOUNTS_NOTIFICATIONS_GROUP,
                                                        BankTopics.NOTIFICATION_RECEIPTS))
                                        .isGreaterThanOrEqualTo(end));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notification_inbox WHERE source='CASH'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM bank_kafka_outbox WHERE payload->>'type'='NOTIFICATION_AVAILABLE'",
                                Integer.class))
                .isEqualTo(1);
    }
}
