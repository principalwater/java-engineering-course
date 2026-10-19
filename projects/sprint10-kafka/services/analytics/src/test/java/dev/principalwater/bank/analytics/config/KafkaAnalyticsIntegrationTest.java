package dev.principalwater.bank.analytics.config;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.principalwater.bank.analytics.AnalyticsTestBase;
import dev.principalwater.bank.analytics.dao.ActivityExportQueue;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.analytics.service.AnalyticsPublisher;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.UiActivityEvent;
import dev.principalwater.bank.contract.UiActivityRequest;
import dev.principalwater.bank.contract.UiActivitySection;
import dev.principalwater.bank.contract.UiActivityType;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

@TestPropertySource(properties = "spring.kafka.security.protocol=PLAINTEXT")
class KafkaAnalyticsIntegrationTest extends AnalyticsTestBase {
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final TopicPartition INGRESS =
            new TopicPartition(AnalyticsProjection.ACCOUNTS_TOPIC, 0);
    private static final TopicPartition PARTITION =
            new TopicPartition(AnalyticsProjection.TOPIC, 0);
    private final Admin admin = Fixture.admin();
    @Autowired private AnalyticsInbox inbox;
    @Autowired private ActivityExportQueue exports;
    @Autowired private AnalyticsPublisher publisher;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", Fixture.BROKER::getBootstrapServers);
    }

    @Override
    protected void resetDatabase() {
        stopConsumers();
        try {
            // Удаляем только записи своего test-container topic, сохраняя тот же broker и Spring
            // context.
            admin.deleteRecords(
                            Map.of(
                                    PARTITION,
                                    RecordsToDelete.beforeOffset(endOffset()),
                                    INGRESS,
                                    RecordsToDelete.beforeOffset(endOffset(INGRESS))))
                    .all()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot reset Kafka fixture", failure);
        }
        super.resetDatabase();
    }

    @AfterEach
    void closeScenarioClients() {
        stopConsumers();
        admin.close(Duration.ofSeconds(5));
    }

    @Test
    void relayDrainsTwoHundredDurableEventsWithinFiniteDeadline() {
        for (int number = 0; number < 200; number++) {
            inbox.acceptBackend(event());
        }
        startConsumers();
        long started = System.nanoTime();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            await().atMost(WAIT)
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            200,
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM analytics_inbox WHERE cassandra_done AND warehouse_done",
                                                    Integer.class)));
            awaitCommitted(endOffset());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(elapsedMillis < WAIT.toMillis());
        System.out.println("Relay drained 200 durable events in " + elapsedMillis + "ms");
    }

    @Test
    void relayRepublishesDurablePendingFactAfterKafkaRecordsAreDeleted() throws Exception {
        BankEvent event = event();
        inbox.acceptBackend(event);
        long firstOffset = endOffset();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            await().atMost(WAIT)
                    .untilAsserted(
                            () -> {
                                assertTrue(endOffset() > firstOffset);
                                assertTrue(
                                        jdbc.queryForObject(
                                                "SELECT attempts > 0 AND lease_token IS NULL FROM analytics_inbox WHERE event_id = ?",
                                                Boolean.class,
                                                event.eventId()));
                            });
        }
        assertFalse(done(event, true));
        assertFalse(done(event, false));
        long removedUntil = endOffset();
        long lowWatermark =
                admin.deleteRecords(Map.of(PARTITION, RecordsToDelete.beforeOffset(removedUntil)))
                        .lowWatermarks()
                        .get(PARTITION)
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .lowWatermark();
        assertTrue(lowWatermark >= removedUntil);
        // Сдвигаем только retry-clock строки fixture: неизменный payload/ID обязан пережить потерю
        // записи Kafka.
        jdbc.update(
                "UPDATE analytics_inbox SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                event.eventId());
        startConsumers();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            awaitAcknowledged(event);
            awaitCommitted(endOffset());
        }
        verify(cassandra, times(1)).insert(event);
        verify(warehouse, times(1)).insert(event);
        assertEquals(
                event,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox WHERE event_id = ?",
                                String.class,
                                event.eventId()),
                        BankEvent.class));
    }

    @Test
    void logicalRedeliveryAndConsumerRestartSkipAlreadyAcknowledgedSinks() {
        BankEvent event = event();
        inbox.acceptBackend(event);
        startConsumers();
        publisher.publish(event);
        awaitAcknowledged(event);
        awaitCommitted(endOffset());
        stopConsumers();
        startConsumers();
        publisher.publish(event);
        awaitCommitted(endOffset());
        verify(cassandra, times(1)).insert(event);
        verify(warehouse, times(1)).insert(event);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analytics_inbox", Integer.class));
    }

    @Test
    void sinkWriteBeforeFailedPgAcknowledgementReplaysSameOffsetAfterConsumerRestart()
            throws Exception {
        BankEvent event = event();
        inbox.acceptBackend(event);
        inbox.acceptUi(
                new UiActivityRequest(
                        UUID.randomUUID(),
                        UiActivityType.SCREEN_OPENED,
                        UiActivitySection.ANALYTICS),
                ALICE);
        when(warehouse.month(java.time.YearMonth.of(2026, 10)))
                .thenReturn(new AnalyticsView(true, List.of(), 1L, 1L, 0L, NOW));
        long offset = endOffset();
        jdbc.execute(
                "ALTER TABLE analytics_inbox ADD CONSTRAINT block_personal_ack CHECK (NOT cassandra_done)");
        try {
            startConsumers();
            publisher.publish(event);
            await().atMost(WAIT)
                    .untilAsserted(
                            () -> {
                                verify(cassandra, atLeast(1)).insert(event);
                                assertTrue(done(event, false));
                            });
            assertFalse(done(event, true));
            assertTrue(committed(AnalyticsProjection.PERSONAL) <= offset);
            http.perform(
                            get("/api/analytics/business")
                                    .header(
                                            AUTHORIZATION,
                                            "Bearer "
                                                    + JWT.analyst(
                                                            "alice-id", "alice", "analytics.read")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true));
            stopConsumers();
        } finally {
            jdbc.execute("ALTER TABLE analytics_inbox DROP CONSTRAINT block_personal_ack");
        }
        startConsumers();
        awaitAcknowledged(event);
        awaitCommitted(offset + 1);
        verify(cassandra, atLeast(2)).insert(event);
        verify(warehouse, times(1)).insert(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "key", "payload", "unknown", "null", "oversize"})
    void poisonIsDurablyQuarantinedAndDoesNotBlockTheNextValidFact(String kind) throws Exception {
        BankEvent event = event();
        inbox.acceptBackend(event);
        String value = mapper.writeValueAsString(event);
        String key = event.ownerId().toString();
        String reason;
        switch (kind) {
            case "malformed" -> {
                value = "{";
                reason = "MALFORMED";
            }
            case "key" -> {
                key = BOB.toString();
                reason = "KEY_MISMATCH";
            }
            case "payload" -> {
                value =
                        mapper.writeValueAsString(
                                new BankEvent(event.eventId(), ALICE, "DEPOSIT", 30, 100, NOW));
                reason = "PAYLOAD_MISMATCH";
            }
            case "unknown" -> {
                value = mapper.writeValueAsString(event());
                reason = "UNKNOWN_EVENT";
            }
            case "null" -> {
                value = null;
                reason = "MALFORMED";
            }
            case "oversize" -> {
                value =
                        mapper.writeValueAsString(
                                new BankEvent(
                                        event.eventId(), ALICE, "A".repeat(5000), 29, 100, NOW));
                reason = "MALFORMED";
            }
            default -> throw new IllegalArgumentException("Unknown Kafka fixture");
        }
        startConsumers();
        RecordMetadata bad =
                kafka.send(AnalyticsProjection.TOPIC, key, value)
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .getRecordMetadata();
        await().atMost(WAIT)
                .untilAsserted(() -> assertEquals(2, quarantineCount(bad.offset(), reason)));
        awaitCommitted(bad.offset() + 1);
        verifyNoInteractions(cassandra, warehouse);
        publisher.publish(event);
        awaitAcknowledged(event);
        awaitCommitted(bad.offset() + 2);
    }

    @Test
    void poisonOffsetWaitsForDurableQuarantineWhenDatabaseIsBlocked() throws Exception {
        long offset = endOffset();
        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE analytics_quarantine IN ACCESS EXCLUSIVE MODE");
            }
            startConsumers();
            kafka.send(AnalyticsProjection.TOPIC, ALICE.toString(), "{")
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            // pg_stat_activity доказывает реальное ожидание обоих INSERT, а не предполагаемую
            // задержку потока.
            await().atMost(WAIT)
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            2,
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                                                            + "AND wait_event_type='Lock' AND query LIKE 'INSERT INTO analytics_quarantine%'",
                                                    Integer.class)));
            for (AnalyticsProjection projection : AnalyticsProjection.values()) {
                assertTrue(committed(projection) <= offset);
            }
            blocker.rollback();
        }
        await().atMost(WAIT)
                .untilAsserted(() -> assertEquals(2, quarantineCount(offset, "MALFORMED")));
        awaitCommitted(offset + 1);
        verifyNoInteractions(cassandra, warehouse);
    }

    private BankEvent event() {
        return new BankEvent(UUID.randomUUID(), ALICE, "DEPOSIT", 29, 100, NOW);
    }

    private int quarantineCount(long offset, String reason) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM analytics_quarantine WHERE record_offset = ? AND reason = ?",
                Integer.class,
                offset,
                reason);
    }

    @Test
    void accountsIngressDurablyAcceptsImmutableBackendEventsAndQuarantinesWrongOriginPayloads()
            throws Exception {
        startConsumers();
        BankEvent profile = new BankEvent(UUID.randomUUID(), ALICE, "PROFILE_UPDATED", 0, 0, NOW);
        long internalBefore = endOffset();
        for (int replay = 0; replay < 2; replay++)
            kafka.send(
                            AnalyticsProjection.ACCOUNTS_TOPIC,
                            ALICE.toString(),
                            mapper.writeValueAsString(profile))
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertEquals(
                                        1,
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM analytics_inbox",
                                                Integer.class)));
        assertEquals(
                profile,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox", String.class),
                        BankEvent.class));
        assertEquals(internalBefore, endOffset());
        verifyNoInteractions(cassandra, warehouse);
        for (BankEvent bad :
                List.of(
                        new BankEvent(profile.eventId(), ALICE, "NOTIFICATION_READ", 0, 0, NOW),
                        new BankEvent(UUID.randomUUID(), ALICE, "SCREEN_OPENED", 0, 0, NOW))) {
            kafka.send(
                            AnalyticsProjection.ACCOUNTS_TOPIC,
                            ALICE.toString(),
                            mapper.writeValueAsString(bad))
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
        kafka.send(
                        AnalyticsProjection.ACCOUNTS_TOPIC,
                        BOB.toString(),
                        mapper.writeValueAsString(event()))
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        BankEvent notification =
                new BankEvent(UUID.randomUUID(), ALICE, "NOTIFICATION_READ", 0, 0, NOW);
        long acceptedOffset =
                kafka.send(
                                AnalyticsProjection.ACCOUNTS_TOPIC,
                                ALICE.toString(),
                                mapper.writeValueAsString(notification))
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .getRecordMetadata()
                        .offset();
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            assertEquals(
                                    2,
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM analytics_inbox", Integer.class));
                            assertEquals(
                                    3,
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM analytics_quarantine WHERE projection = 'INGRESS'",
                                            Integer.class));
                            OffsetAndMetadata offset =
                                    admin.listConsumerGroupOffsets(
                                                    AnalyticsProjection.INGRESS_GROUP)
                                            .partitionsToOffsetAndMetadata()
                                            .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                                            .get(INGRESS);
                            assertTrue(offset != null && offset.offset() > acceptedOffset);
                        });
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            awaitAcknowledged(profile);
            awaitAcknowledged(notification);
        }
        verify(cassandra).insert(profile);
        verify(warehouse).insert(notification);
    }

    @Test
    void signedUiUuidCannotPreclaimAFutureTrustedFinancialEvent() throws Exception {
        BankEvent financial = event();
        UiActivityRequest action =
                new UiActivityRequest(
                        financial.eventId(),
                        UiActivityType.SCREEN_OPENED,
                        UiActivitySection.ANALYTICS);
        for (String principal : List.of("alice", "bob"))
            http.perform(
                            post("/api/analytics/activities")
                                    .header(
                                            AUTHORIZATION,
                                            "Bearer "
                                                    + JWT.user(
                                                            principal + "-id",
                                                            principal,
                                                            "analytics.events"))
                                    .contentType(APPLICATION_JSON)
                                    .content(mapper.writeValueAsBytes(action)))
                    .andExpect(status().isNoContent());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM analytics_inbox", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE event_id = ?",
                        Integer.class,
                        financial.eventId()));
        startConsumers();
        kafka.send(
                        AnalyticsProjection.ACCOUNTS_TOPIC,
                        ALICE.toString(),
                        mapper.writeValueAsString(financial))
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertEquals(
                                        3,
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM analytics_inbox",
                                                Integer.class)));
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            awaitAcknowledged(financial);
        }
        assertEquals(
                financial,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox WHERE event_id = ?",
                                String.class,
                                financial.eventId()),
                        BankEvent.class));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM analytics_quarantine", Integer.class));
        verify(cassandra).insert(financial);
        verify(warehouse).insert(financial);
    }

    @Test
    void accountsIngressQuarantinesStrictMinorWireFailuresBeforeAcceptingTheNextValidFact()
            throws Exception {
        startConsumers();
        for (String field : List.of("amountMinor", "balanceMinor")) {
            for (String kind : List.of("string", "null", "missing", "exponent")) {
                ObjectNode body = mapper.valueToTree(event());
                switch (kind) {
                    case "string" -> body.put(field, body.get(field).asText());
                    case "null" -> body.putNull(field);
                    case "missing", "exponent" -> body.remove(field);
                    default -> throw new IllegalArgumentException("Unknown wire fixture");
                }
                String json = mapper.writeValueAsString(body);
                if (kind.equals("exponent"))
                    json = json.substring(0, json.length() - 1) + ",\"" + field + "\":1e2}";
                kafka.send(AnalyticsProjection.ACCOUNTS_TOPIC, ALICE.toString(), json)
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            }
        }
        BankEvent valid = event();
        long accepted =
                kafka.send(
                                AnalyticsProjection.ACCOUNTS_TOPIC,
                                ALICE.toString(),
                                mapper.writeValueAsString(valid))
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .getRecordMetadata()
                        .offset();
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            assertEquals(
                                    8,
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM analytics_quarantine WHERE projection = 'INGRESS' AND reason = 'MALFORMED'",
                                            Integer.class));
                            assertEquals(
                                    1,
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM analytics_inbox", Integer.class));
                            assertTrue(ingressCommitted() > accepted);
                        });
        assertEquals(
                valid,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox", String.class),
                        BankEvent.class));
        verifyNoInteractions(cassandra, warehouse);
    }

    @Test
    void accountsIngressOffsetWaitsForTheDurableInboxTransaction() throws Exception {
        BankEvent event = event();
        long offset = endOffset(INGRESS);
        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("LOCK TABLE analytics_inbox IN ACCESS EXCLUSIVE MODE");
            }
            startConsumers();
            kafka.send(
                            AnalyticsProjection.ACCOUNTS_TOPIC,
                            ALICE.toString(),
                            mapper.writeValueAsString(event))
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            await().atMost(WAIT)
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            1,
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO analytics_inbox%'",
                                                    Integer.class)));
            assertTrue(ingressCommitted() <= offset);
            blocker.rollback();
        }
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            assertEquals(
                                    1,
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM analytics_inbox", Integer.class));
                            assertTrue(ingressCommitted() > offset);
                        });
        assertEquals(
                event,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox", String.class),
                        BankEvent.class));
    }

    private long ingressCommitted() throws Exception {
        OffsetAndMetadata offset =
                admin.listConsumerGroupOffsets(AnalyticsProjection.INGRESS_GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                        .get(INGRESS);
        return offset == null ? 0 : offset.offset();
    }

    @Test
    void typedUiReplayProjectsToItsOwnCassandraTableAndLeavesWarehouseAckToTheDurableBatch() {
        UiActivityRequest request =
                new UiActivityRequest(
                        UUID.randomUUID(), UiActivityType.FORM_OPENED, UiActivitySection.PAYMENTS);
        inbox.acceptUi(request, ALICE);
        inbox.acceptUi(request, ALICE);
        UUID storedId = jdbc.queryForObject("SELECT event_id FROM analytics_inbox", UUID.class);
        startConsumers();
        try (AnalyticsRelay.Workers relay = new AnalyticsRelay.Workers(inbox, publisher)) {
            relay.start();
            await().atMost(WAIT)
                    .untilAsserted(
                            () ->
                                    assertTrue(
                                            Boolean.TRUE.equals(
                                                    jdbc.queryForObject(
                                                            "SELECT cassandra_done FROM analytics_inbox WHERE event_id = ?",
                                                            Boolean.class,
                                                            storedId))));
        }
        verify(uiActivity)
                .insert(
                        new UiActivityEvent(
                                storedId, ALICE, request.type(), request.section(), NOW));
        verifyNoInteractions(cassandra, warehouse);
        assertFalse(
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT warehouse_done FROM analytics_inbox WHERE event_id = ?",
                                Boolean.class,
                                storedId)));
        ActivityExportQueue.Export export = exports.claim();
        assertEquals(ALICE, export.ownerId());
        assertEquals(java.time.YearMonth.of(2026, 10), export.month());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM analytics_inbox", Integer.class));
    }

    private boolean done(BankEvent event, boolean personal) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT CASE WHEN ? THEN cassandra_done ELSE warehouse_done END "
                                + "FROM analytics_inbox WHERE event_id = ?",
                        Boolean.class,
                        personal,
                        event.eventId()));
    }

    private void awaitAcknowledged(BankEvent event) {
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            assertTrue(done(event, true));
                            assertTrue(done(event, false));
                        });
    }

    private void startConsumers() {
        listeners.getListenerContainers().forEach(container -> container.start());
    }

    private void stopConsumers() {
        listeners.getListenerContainers().forEach(container -> container.stop());
    }

    private long endOffset() {
        return endOffset(PARTITION);
    }

    private long endOffset(TopicPartition partition) {
        try {
            ListOffsetsResult.ListOffsetsResultInfo result =
                    admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                            .partitionResult(partition)
                            .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return result.offset();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Kafka end offset", failure);
        }
    }

    private long committed(AnalyticsProjection projection) {
        try {
            OffsetAndMetadata offset =
                    admin.listConsumerGroupOffsets(projection.getGroupId())
                            .partitionsToOffsetAndMetadata()
                            .get(WAIT.toSeconds(), TimeUnit.SECONDS)
                            .get(PARTITION);
            return offset == null ? -1 : offset.offset();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Kafka committed offset", failure);
        }
    }

    private void awaitCommitted(long nextOffset) {
        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            for (AnalyticsProjection projection : AnalyticsProjection.values()) {
                                assertTrue(committed(projection) >= nextOffset);
                            }
                        });
    }

    private static final class Fixture {
        private static final Duration ADMIN_REQUEST_TIMEOUT = Duration.ofSeconds(5);
        private static final Duration ADMIN_API_TIMEOUT = Duration.ofSeconds(10);
        private static final KafkaContainer BROKER =
                new KafkaContainer("apache/kafka:4.2.2")
                        .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                        .withCreateContainerCmdModifier(
                                command ->
                                        command.getHostConfig()
                                                .withMemory(768L * 1024 * 1024)
                                                .withNanoCPUs(1_000_000_000L));

        static {
            BROKER.start();
            try (Admin admin = admin()) {
                admin.createTopics(
                                List.of(
                                        new NewTopic(AnalyticsProjection.TOPIC, 1, (short) 1),
                                        new NewTopic(
                                                AnalyticsProjection.ACCOUNTS_TOPIC, 1, (short) 1)))
                        .all()
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot initialize Kafka fixture", failure);
            }
        }

        private static Admin admin() {
            return Admin.create(
                    Map.of(
                            "bootstrap.servers",
                            BROKER.getBootstrapServers(),
                            "request.timeout.ms",
                            Math.toIntExact(ADMIN_REQUEST_TIMEOUT.toMillis()),
                            "default.api.timeout.ms",
                            Math.toIntExact(ADMIN_API_TIMEOUT.toMillis())));
        }
    }
}
