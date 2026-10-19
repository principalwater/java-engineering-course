package dev.principalwater.bank.notifications.dao;

import static org.assertj.core.api.Assertions.assertThat;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.notifications.NotificationsTestBase;
import dev.principalwater.bank.testing.MigrationFixture;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiptMigrationTest extends NotificationsTestBase {
    @Test
    void existingS9ReceiptsBackfillEveryCompositeSourceWithoutDeletingHistory() throws Exception {
        try (MigrationFixture fixture =
                new MigrationFixture(
                        jdbc.getDataSource(), "classpath:db/notifications", "classpath:db/kafka")) {
            fixture.migrate("2");
            UUID eventId = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            for (NotificationSource source :
                    new NotificationSource[] {
                        NotificationSource.CASH, NotificationSource.TRANSFER
                    }) {
                BankEvent event =
                        new BankEvent(
                                eventId,
                                owner,
                                source == NotificationSource.CASH
                                        ? "DEPOSIT_SUCCEEDED"
                                        : "TRANSFER_SUCCEEDED",
                                100,
                                100,
                                Instant.parse("2026-10-17T12:00:00Z"));
                fixture.jdbc()
                        .update(
                                "INSERT INTO bank_notification(caller,event_id,payload) VALUES (?,?,?::jsonb)",
                                source.getCaller(),
                                eventId,
                                mapper.writeValueAsString(event));
            }
            fixture.migrate("latest");
            assertThat(
                            fixture.jdbc()
                                    .queryForObject(
                                            "SELECT count(*) FROM bank_notification",
                                            Integer.class))
                    .isEqualTo(2);
            assertThat(
                            fixture.jdbc()
                                    .queryForList(
                                            "SELECT message_id FROM bank_kafka_outbox",
                                            String.class))
                    .containsExactlyInAnyOrder(
                            NotificationSource.CASH.identity(eventId),
                            NotificationSource.TRANSFER.identity(eventId));
            for (String json :
                    fixture.jdbc()
                            .queryForList(
                                    "SELECT payload::text FROM bank_kafka_outbox", String.class)) {
                NotificationReceipt receipt = mapper.readValue(json, NotificationReceipt.class);
                assertThat(receipt.event().ownerId()).isEqualTo(owner);
                assertThat(receipt.event().eventId()).isEqualTo(eventId);
            }
        }
    }
}
