package dev.principalwater.bank.notifications.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.principalwater.bank.notifications.NotificationsTestBase;
import dev.principalwater.bank.testing.KafkaContractConfiguration;
import dev.principalwater.bank.testing.KafkaFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.context.annotation.Import;

@Import(KafkaContractConfiguration.class)
@AutoConfigureStubRunner(
        ids = {
            "dev.principalwater.bank:accounts:1.0.0:stubs",
            "dev.principalwater.bank:cash:1.0.0:stubs",
            "dev.principalwater.bank:transfer:1.0.0:stubs"
        },
        stubsMode = StubRunnerProperties.StubsMode.CLASSPATH)
class ProducerMessagingContractTest extends NotificationsTestBase {
    @Autowired private StubFinder stubs;

    @AfterEach
    void stop() {
        stopConsumers();
    }

    @ParameterizedTest
    @CsvSource({
        "accounts,accounts-service,PROFILE_UPDATED",
        "cash,cash-service,DEPOSIT_SUCCEEDED",
        "transfer,transfer-service,TRANSFER_SUCCEEDED"
    })
    void productionListenerConsumesEachVerifiedProducer(
            String producer, String caller, String type) {
        startConsumers();
        assertThat(stubs.trigger(producer + "_notification")).isTrue();
        await().atMost(KafkaFixture.WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM bank_notification WHERE caller=? AND payload->>'type'=?",
                                                        Integer.class,
                                                        caller,
                                                        type))
                                        .isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_kafka_outbox", Integer.class))
                .isEqualTo(1);
    }
}
