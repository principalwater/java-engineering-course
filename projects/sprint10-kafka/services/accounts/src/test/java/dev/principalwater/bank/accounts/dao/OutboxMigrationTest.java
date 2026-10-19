package dev.principalwater.bank.accounts.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.principalwater.bank.accounts.AccountsTestBase;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.testing.MigrationFixture;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

class OutboxMigrationTest extends AccountsTestBase {
    @Test
    void pendingLegacyRoutesMoveWithoutChangingIdentityAndDeliveredRowsStayHistorical()
            throws Exception {
        try (MigrationFixture fixture =
                new MigrationFixture(
                        jdbc.getDataSource(),
                        "classpath:db/accounts",
                        "classpath:db/delivery",
                        "classpath:db/kafka")) {
            fixture.migrate("900");
            UUID id = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            String event =
                    mapper.writeValueAsString(
                            new BankEvent(
                                    eventId,
                                    owner,
                                    "DEPOSIT",
                                    100,
                                    100,
                                    Instant.parse("2026-10-17T12:00:00Z")));
            fixture.jdbc()
                    .update(
                            "INSERT INTO bank_outbox(id,event_id,target,path,payload) VALUES (?,?,?,'/internal/analytics/events',?::jsonb)",
                            id,
                            eventId,
                            "analytics",
                            event);
            fixture.jdbc()
                    .update(
                            "INSERT INTO bank_outbox(id,event_id,target,path,payload,status) VALUES (?,?,?,'/internal/notifications',?::jsonb,'DELIVERED')",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "notifications",
                            event);
            fixture.migrate("latest");
            assertThat(
                            fixture.jdbc()
                                    .queryForObject("SELECT id FROM bank_kafka_outbox", UUID.class))
                    .isEqualTo(id);
            assertThat(
                            fixture.jdbc()
                                    .queryForObject(
                                            "SELECT message_id FROM bank_kafka_outbox",
                                            String.class))
                    .isEqualTo(eventId.toString());
            assertThat(
                            fixture.jdbc()
                                    .queryForObject(
                                            "SELECT topic FROM bank_kafka_outbox", String.class))
                    .isEqualTo("bank.accounts.analytics.v1");
            assertThat(
                            fixture.jdbc()
                                    .queryForObject(
                                            "SELECT payload=?::jsonb FROM bank_kafka_outbox",
                                            Boolean.class,
                                            event))
                    .isTrue();
            assertThat(fixture.jdbc().queryForList("SELECT status FROM bank_outbox", String.class))
                    .containsExactlyInAnyOrder("DELIVERED", "MIGRATED");
        }
    }

    @Test
    void unsupportedPendingRouteBlocksUpgradeRatherThanDiscardingSavedFact() throws Exception {
        try (MigrationFixture fixture =
                new MigrationFixture(
                        jdbc.getDataSource(),
                        "classpath:db/accounts",
                        "classpath:db/delivery",
                        "classpath:db/kafka")) {
            fixture.migrate("900");
            fixture.jdbc()
                    .update(
                            "INSERT INTO bank_outbox(id,event_id,target,path,payload) VALUES (?,?,?,'/unexpected','{}'::jsonb)",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "analytics");
            assertThatThrownBy(() -> fixture.migrate("latest")).isInstanceOf(FlywayException.class);
            assertThat(
                            fixture.jdbc()
                                    .queryForObject("SELECT status FROM bank_outbox", String.class))
                    .isEqualTo("PENDING");
        }
    }
}
