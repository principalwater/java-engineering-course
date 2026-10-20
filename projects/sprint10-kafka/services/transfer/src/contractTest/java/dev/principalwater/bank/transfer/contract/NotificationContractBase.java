package dev.principalwater.bank.transfer.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.contract.OperationResult;
import dev.principalwater.bank.contract.OperationStatus;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.messaging.KafkaMessagingConfiguration;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.runtime.workflow.IntentRepository;
import dev.principalwater.bank.runtime.workflow.MoneyWorkflow;
import dev.principalwater.bank.testing.BankFixtures;
import dev.principalwater.bank.testing.KafkaContractConfiguration;
import dev.principalwater.bank.testing.KafkaFixture;
import dev.principalwater.bank.testing.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = NotificationContractBase.ContractApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=transfer_contract",
            "spring.config.import=classpath:bank-common.properties,classpath:bank-kafka.properties",
            "spring.kafka.security.protocol=PLAINTEXT",
            "spring.flyway.locations=classpath:db/kafka,classpath:db/workflow"
        })
public abstract class NotificationContractBase extends PostgresTestBase {
    @Autowired private KafkaOutbox outbox;
    @Autowired private PlatformTransactionManager transactions;

    static {
        KafkaFixture.topics(NotificationSource.TRANSFER.getTopic());
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KafkaFixture::bootstrapServers);
    }

    @Override
    protected void resetDatabase() {
        jdbc.execute("TRUNCATE bank_intent,bank_kafka_outbox");
        KafkaFixture.clear(NotificationSource.TRANSFER.getTopic());
    }

    public void publishNotification() {
        UUID id = UUID.fromString("1582c2bb-3d60-4aae-bb70-d30872652374");
        InternalOperation operation =
                new InternalOperation(
                        BankFixtures.actor("alice"),
                        id,
                        OperationType.TRANSFER,
                        500L,
                        UUID.fromString("c465e4cf-4080-480f-9fdd-5eb550be9ff9"));
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        ServiceClient accounts = mock(ServiceClient.class);
        when(accounts.post(eq("accounts"), any(), eq(operation), eq(OperationResult.class)))
                .thenReturn(
                        new OperationResult(
                                id,
                                OperationStatus.SUCCEEDED,
                                null,
                                UUID.fromString("5808b517-d97b-4fdc-afc0-8f983fb28bc7"),
                                4500L,
                                now));
        MoneyWorkflow workflow =
                new MoneyWorkflow(
                        new IntentRepository(jdbc, mapper),
                        accounts,
                        outbox,
                        new TransactionTemplate(transactions),
                        Clock.fixed(now, ZoneOffset.UTC));
        workflow.execute(operation);
        outbox.pendingIds().forEach(outbox::recover);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({KafkaMessagingConfiguration.class, KafkaContractConfiguration.class})
    static class ContractApp {}
}
