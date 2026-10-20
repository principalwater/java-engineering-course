package dev.principalwater.bank.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.BankTopics;
import dev.principalwater.bank.contract.BusinessFailureEvent;
import dev.principalwater.bank.contract.BusinessFailureType;
import dev.principalwater.bank.contract.NotificationKind;
import dev.principalwater.bank.contract.NotificationReceipt;
import dev.principalwater.bank.contract.NotificationSource;
import dev.principalwater.bank.messaging.KafkaOutbox;
import dev.principalwater.bank.messaging.MessageCodec;
import dev.principalwater.bank.notifications.NotificationsApplication;
import dev.principalwater.bank.notifications.NotificationsTestBase;
import dev.principalwater.bank.testing.KafkaFixture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;

@ExtendWith(OutputCaptureExtension.class)
class NotificationKafkaTest extends NotificationsTestBase {
    private static final String LOGIN = "notification-owner";
    private static final String RECEIPT_PUBLISH_FAILED = "receipt_publish_failed";
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private KafkaOutbox outbox;
    @Autowired private MessageCodec codec;
    @Autowired private MeterRegistry meters;
    @Autowired private ApplicationContext context;
    @Autowired private NotificationService notifications;
    @Autowired private NotificationReplay replay;
    @Autowired private NotificationListener listener;
    @Autowired private ConcurrentKafkaListenerContainerFactory<String, String> factory;

    @AfterEach
    void stop() {
        stopConsumers();
    }

    @Test
    void allActionsAreDurableAndDuplicateSourceIdentitySurvivesConsumerRestart(
            CapturedOutput output) throws Exception {
        startConsumers();
        UUID eventId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        for (NotificationKind kind : NotificationKind.values())
            send(
                    kind.getSource(),
                    new BankEvent(
                            kind == NotificationKind.TRANSFER_SUCCEEDED
                                    ? eventId
                                    : UUID.randomUUID(),
                            owner,
                            kind.name(),
                            100,
                            500,
                            Instant.parse("2026-10-17T12:00:00Z")));
        BankEvent cash =
                new BankEvent(
                        eventId,
                        owner,
                        "DEPOSIT_SUCCEEDED",
                        100,
                        500,
                        Instant.parse("2026-10-17T12:00:00Z"));
        send(NotificationSource.CASH, cash);
        send(NotificationSource.CASH, cash);
        await().atMost(KafkaFixture.WAIT).untilAsserted(() -> assertThat(count()).isEqualTo(8));
        awaitCommitted(NotificationSource.CASH);
        stopConsumers();
        startConsumers();
        send(NotificationSource.CASH, cash);
        awaitCommitted(NotificationSource.CASH);
        assertThat(count()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_kafka_outbox", Integer.class))
                .isEqualTo(8);
        outbox.pendingIds().forEach(outbox::recover);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM bank_kafka_outbox WHERE status='PUBLISHED'",
                                Integer.class))
                .isEqualTo(8);
        var receipts = KafkaFixture.records(BankTopics.NOTIFICATION_RECEIPTS, 8);
        assertThat(receipts).hasSize(8);
        assertThat(
                        receipts.stream()
                                .map(
                                        record -> {
                                            try {
                                                return mapper.readValue(
                                                        record.value(), NotificationReceipt.class);
                                            } catch (Exception failure) {
                                                throw new AssertionError(failure);
                                            }
                                        })
                                .filter(receipt -> receipt.event().eventId().equals(eventId))
                                .map(NotificationReceipt::source)
                                .toList())
                .containsExactlyInAnyOrder(NotificationSource.CASH, NotificationSource.TRANSFER);
        assertThat(
                        context.getBeanNamesForType(
                                org.springframework.boot.web.context.WebServerApplicationContext
                                        .class))
                .isEmpty();
        assertThat(context.containsBean("clientRegistrationRepository")).isFalse();
        assertThat(
                        output.getAll()
                                .lines()
                                .filter(line -> line.contains("Notification action="))
                                .count())
                .isEqualTo(8);
        assertThat(output.getAll()).doesNotContain(owner.toString(), eventId.toString());
    }

    @Test
    void malformedForgedSourceKeyAndChangedPayloadGoToDurableDltBeforeNextValidEvent(
            CapturedOutput output) throws Exception {
        double rejected = failures("_unknown", "event_rejected");
        startConsumers();
        BankEvent original = event("DEPOSIT_SUCCEEDED");
        send(NotificationSource.CASH, original);
        await().atMost(KafkaFixture.WAIT).untilAsserted(() -> assertThat(count()).isEqualTo(1));
        List<String> poison =
                List.of(
                        "{",
                        mapper.writeValueAsString(
                                new BankEvent(
                                        original.eventId(),
                                        original.ownerId(),
                                        original.type(),
                                        101,
                                        500,
                                        original.occurredAt())),
                        mapper.writeValueAsString(
                                new BankEvent(
                                        UUID.randomUUID(),
                                        original.ownerId(),
                                        "TRANSFER_SUCCEEDED",
                                        100,
                                        500,
                                        original.occurredAt())),
                        mapper.writeValueAsString(
                                new BankEvent(
                                        UUID.randomUUID(),
                                        original.ownerId(),
                                        "UNKNOWN_ACTION",
                                        100,
                                        500,
                                        original.occurredAt())),
                        mapper.writeValueAsString(
                                new BankEvent(
                                        UUID.randomUUID(),
                                        original.ownerId(),
                                        "DEPOSIT_SUCCEEDED\0",
                                        100,
                                        500,
                                        original.occurredAt())));
        for (String payload : poison)
            kafka.send(NotificationSource.CASH.getTopic(), original.ownerId().toString(), payload)
                    .get(5, TimeUnit.SECONDS);
        BankEvent wrongKey = event("DEPOSIT_SUCCEEDED");
        kafka.send(
                        NotificationSource.CASH.getTopic(),
                        UUID.randomUUID().toString(),
                        mapper.writeValueAsString(wrongKey))
                .get(5, TimeUnit.SECONDS);
        BankEvent valid = event("WITHDRAW_SUCCEEDED");
        send(NotificationSource.CASH, valid);
        await().atMost(KafkaFixture.WAIT).untilAsserted(() -> assertThat(count()).isEqualTo(2));
        awaitCommitted(NotificationSource.CASH);
        assertThat(
                        KafkaFixture.records(
                                NotificationSource.CASH.getTopic() + BankTopics.DLT_SUFFIX, 6))
                .hasSize(6);
        // Каждая отброшенная запись считается один раз; логин берётся только из проверенного
        // события, поэтому отказы остаются под неизвестным логином.
        assertThat(failures("_unknown", "event_rejected") - rejected).isEqualTo(6);
        assertThat(output.getAll())
                .doesNotContain(
                        original.ownerId().toString(),
                        original.eventId().toString(),
                        "UNKNOWN_ACTION");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'amountMinor' FROM bank_notification WHERE event_id=?",
                                String.class,
                                original.eventId()))
                .isEqualTo("100");
    }

    @Test
    void failedReceiptOutboxCommitKeepsOffsetUnacknowledgedAndRestartRecoversOnce()
            throws Exception {
        BankEvent event = event("DEPOSIT_SUCCEEDED");
        long offset = KafkaFixture.endOffset(NotificationSource.CASH.getTopic());
        double unavailable = failures(LOGIN, "receive_unavailable");
        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE bank_kafka_outbox IN ACCESS EXCLUSIVE MODE");
            }
            startConsumers();
            send(NotificationSource.CASH, event);
            await().atMost(KafkaFixture.WAIT)
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO bank_kafka_outbox%'",
                                                            Integer.class))
                                            .isPositive());
            // Реальный lock/55P03 не является poison: неподтверждённый receipt остаётся тем же
            // offset.
            await().during(java.time.Duration.ofMillis(500))
                    .atMost(KafkaFixture.WAIT)
                    .untilAsserted(
                            () -> {
                                assertThat(count()).isZero();
                                assertThat(
                                                KafkaFixture.committed(
                                                        BankTopics.NOTIFICATIONS_GROUP,
                                                        NotificationSource.CASH.getTopic()))
                                        .isLessThanOrEqualTo(offset);
                            });
            stopConsumers();
            // Сбой попытки виден метрикой, хотя ошибка ушла в retry без DLT и без подтверждения.
            await().atMost(KafkaFixture.WAIT)
                    .untilAsserted(
                            () ->
                                    assertThat(failures(LOGIN, "receive_unavailable"))
                                            .isGreaterThan(unavailable));
            blocker.rollback();
        }
        startConsumers();
        await().atMost(KafkaFixture.WAIT).untilAsserted(() -> assertThat(count()).isEqualTo(1));
        awaitCommitted(NotificationSource.CASH);
        send(NotificationSource.CASH, event);
        awaitCommitted(NotificationSource.CASH);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_kafka_outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void deterministicPayloadConstraintFailureIsDltInsteadOfBlockingValidFollower()
            throws Exception {
        jdbc.execute(
                "ALTER TABLE bank_notification ADD CONSTRAINT fixture_payload CHECK(payload->>'type'<>'WITHDRAW_SUCCEEDED')");
        try {
            startConsumers();
            send(NotificationSource.CASH, event("WITHDRAW_SUCCEEDED"));
            send(NotificationSource.CASH, event("DEPOSIT_SUCCEEDED"));
            awaitCommitted(NotificationSource.CASH);
            assertThat(count()).isEqualTo(1);
            assertThat(
                            KafkaFixture.records(
                                    NotificationSource.CASH.getTopic() + BankTopics.DLT_SUFFIX, 1))
                    .hasSize(1);
        } finally {
            stopConsumers();
            jdbc.execute("ALTER TABLE bank_notification DROP CONSTRAINT fixture_payload");
        }
    }

    @Test
    void boundedDatabaseReplayRebuildsExpiredBrokerHistoryWithStableCompositeIdentity()
            throws Exception {
        BankEvent cash = event("DEPOSIT_SUCCEEDED");
        BankEvent transfer =
                new BankEvent(
                        cash.eventId(),
                        cash.ownerId(),
                        "TRANSFER_SUCCEEDED",
                        100,
                        500,
                        cash.occurredAt());
        notifications.receive(NotificationSource.CASH, cash);
        notifications.receive(NotificationSource.TRANSFER, transfer);
        outbox.pendingIds().forEach(outbox::recover);
        assertThat(KafkaFixture.records(BankTopics.NOTIFICATION_RECEIPTS, 2)).hasSize(2);
        KafkaFixture.clear(BankTopics.NOTIFICATION_RECEIPTS);
        NotificationReplay.Page first = replay.requeue(0, 1);
        NotificationReplay.Page second = replay.requeue(first.nextSequence(), 1);
        assertThat(first.count()).isEqualTo(1);
        assertThat(second.count()).isEqualTo(1);
        assertThat(replay.requeue(second.nextSequence(), 1).count()).isZero();
        outbox.pendingIds().forEach(outbox::recover);
        var restored = KafkaFixture.records(BankTopics.NOTIFICATION_RECEIPTS, 2);
        assertThat(restored).hasSize(2);
        assertThat(
                        restored.stream()
                                .map(
                                        record -> {
                                            try {
                                                return mapper.readValue(
                                                        record.value(), NotificationReceipt.class);
                                            } catch (Exception failure) {
                                                throw new AssertionError(failure);
                                            }
                                        })
                                .map(
                                        receipt ->
                                                receipt.source()
                                                        .identity(receipt.event().eventId()))
                                .toList())
                .containsExactlyInAnyOrder(
                        NotificationSource.CASH.identity(cash.eventId()),
                        NotificationSource.TRANSFER.identity(cash.eventId()));
        assertThat(count()).isEqualTo(2);
        assertThatThrownBy(() -> replay.requeue(0, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secondConsumerRebalancesAndOwnerReceiptEffectsRemainIdempotent() throws Exception {
        var second =
                factory.createContainer(
                        NotificationSource.ACCOUNTS.getTopic(),
                        NotificationSource.CASH.getTopic(),
                        NotificationSource.TRANSFER.getTopic());
        second.getContainerProperties().setGroupId(BankTopics.NOTIFICATIONS_GROUP);
        second.setupMessageListener((MessageListener<String, String>) listener::receive);
        try {
            startConsumers();
            BankEvent event = event("DEPOSIT_SUCCEEDED");
            send(NotificationSource.CASH, event);
            awaitCommitted(NotificationSource.CASH);
            second.start();
            await().atMost(KafkaFixture.WAIT)
                    .untilAsserted(
                            () -> {
                                assertThat(second.getAssignedPartitions()).isNotEmpty();
                                assertThat(
                                                listeners.getListenerContainers().stream()
                                                        .anyMatch(
                                                                container ->
                                                                        container
                                                                                                .getAssignedPartitions()
                                                                                        != null
                                                                                && !container
                                                                                        .getAssignedPartitions()
                                                                                        .isEmpty()))
                                        .isTrue();
                            });
            send(NotificationSource.CASH, event);
            awaitCommitted(NotificationSource.CASH);
            stopConsumers();
            await().atMost(KafkaFixture.WAIT)
                    .untilAsserted(() -> assertThat(second.getAssignedPartitions()).hasSize(6));
            send(NotificationSource.CASH, event);
            send(NotificationSource.CASH, event("WITHDRAW_SUCCEEDED"));
            awaitCommitted(NotificationSource.CASH);
            assertThat(count()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_kafka_outbox", Integer.class))
                    .isEqualTo(2);
        } finally {
            second.stop();
        }
    }

    @Test
    void poisonOffsetWaitsUntilFailedDltPublicationCanObtainDurableBrokerAck() throws Exception {
        String topic = NotificationSource.CASH.getTopic();
        String dlt = topic + BankTopics.DLT_SUFFIX;
        long before = KafkaFixture.endOffset(topic);
        try (Admin admin = KafkaFixture.admin()) {
            admin.deleteTopics(List.of(dlt)).all().get(10, TimeUnit.SECONDS);
            await().atMost(KafkaFixture.WAIT)
                    .until(
                            () ->
                                    !admin.listTopics()
                                            .names()
                                            .get(10, TimeUnit.SECONDS)
                                            .contains(dlt));
            double errors = producerErrors();
            try {
                startConsumers();
                kafka.send(topic, UUID.randomUUID().toString(), "{").get(5, TimeUnit.SECONDS);
                await().atMost(KafkaFixture.WAIT)
                        .untilAsserted(() -> assertThat(producerErrors()).isGreaterThan(errors));
                assertThat(KafkaFixture.committed(BankTopics.NOTIFICATIONS_GROUP, topic))
                        .isLessThanOrEqualTo(before);
                assertThat(count()).isZero();
            } finally {
                KafkaFixture.topics(dlt);
            }
            awaitCommitted(NotificationSource.CASH);
            assertThat(KafkaFixture.records(dlt, 1)).hasSize(1);
            assertThat(count()).isZero();
        }
    }

    @Test
    void operatedReplayProfileQueuesOnePageAndClosesOnlyItsOwnApplication() throws Exception {
        BankEvent event = event("DEPOSIT_SUCCEEDED");
        notifications.receive(NotificationSource.CASH, event);
        outbox.pendingIds().forEach(outbox::recover);
        HikariDataSource database = jdbc.getDataSource().unwrap(HikariDataSource.class);
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "replayFixture",
                                Map.ofEntries(
                                        Map.entry(
                                                "spring.config.import",
                                                "classpath:bank-common.properties,classpath:bank-kafka.properties"),
                                        Map.entry("spring.datasource.url", database.getJdbcUrl()),
                                        Map.entry(
                                                "spring.datasource.username",
                                                database.getUsername()),
                                        Map.entry(
                                                "spring.datasource.password",
                                                database.getPassword()),
                                        Map.entry("spring.cloud.zookeeper.enabled", false),
                                        Map.entry("spring.cloud.zookeeper.config.enabled", false),
                                        Map.entry(
                                                "spring.cloud.zookeeper.discovery.enabled", false),
                                        Map.entry("bank.kafka.user", "fixture-service"),
                                        Map.entry("bank.kafka.password", "fixture-password"),
                                        Map.entry("spring.kafka.security.protocol", "PLAINTEXT"),
                                        Map.entry("bank.notification.replay.limit", 1))));
        SpringApplication application = new SpringApplication(NotificationsApplication.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("notification-replay");
        application.setRegisterShutdownHook(false);
        try (var replayContext = application.run()) {
            assertThat(replayContext.isActive()).isFalse();
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bank_kafka_outbox", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void unpublishedReceiptIsCountedByLoginWithOneFactAndRecoversWithoutDuplicate(
            CapturedOutput output) throws Exception {
        String receipts = BankTopics.NOTIFICATION_RECEIPTS;
        BankEvent event = event("DEPOSIT_SUCCEEDED");
        double failed = failures(LOGIN, RECEIPT_PUBLISH_FAILED);
        double gaps = factGaps();
        try (Admin admin = KafkaFixture.admin()) {
            admin.deleteTopics(List.of(receipts)).all().get(10, TimeUnit.SECONDS);
            await().atMost(KafkaFixture.WAIT)
                    .until(
                            () ->
                                    !admin.listTopics()
                                            .names()
                                            .get(10, TimeUnit.SECONDS)
                                            .contains(receipts));
            try {
                notifications.receive(NotificationSource.CASH, event);
                // БД отвергает только факт отказа: исходный receipt обязан остаться в outbox на
                // повтор, а пропуск факта - стать видимым счётчиком.
                jdbc.execute(
                        "ALTER TABLE bank_kafka_outbox ADD CONSTRAINT fixture_no_facts CHECK(topic<>'"
                                + BankTopics.BUSINESS_FAILURES
                                + "')");
                try {
                    deliverPending();
                } finally {
                    jdbc.execute("ALTER TABLE bank_kafka_outbox DROP CONSTRAINT fixture_no_facts");
                }
                assertThat(failures(LOGIN, RECEIPT_PUBLISH_FAILED)).isEqualTo(failed + 1);
                assertThat(factGaps()).isEqualTo(gaps + 1);
                assertThat(facts()).isEmpty();
                assertThat(
                                jdbc.queryForMap(
                                        "SELECT status,attempts FROM bank_kafka_outbox WHERE topic=?",
                                        receipts))
                        .containsEntry("status", "PENDING")
                        .containsEntry("attempts", 1);
                // Повторные отказы той же записи дают один и тот же факт; отказ публикации самого
                // факта (его топика нет) не порождает новых фактов и не попадает в счётчик.
                deliverPending();
                deliverPending();
                assertThat(failures(LOGIN, RECEIPT_PUBLISH_FAILED)).isEqualTo(failed + 3);
                assertThat(factGaps()).isEqualTo(gaps + 1);
                assertThat(facts())
                        .singleElement()
                        .satisfies(
                                fact -> {
                                    assertThat(fact.type())
                                            .isEqualTo(BusinessFailureType.NOTIFICATION);
                                    assertThat(fact.ownerId()).isEqualTo(event.ownerId());
                                    assertThat(fact.reason()).isEqualTo(RECEIPT_PUBLISH_FAILED);
                                    assertThat(fact.requestedAmountMinor()).isZero();
                                });
            } finally {
                KafkaFixture.topics(receipts);
            }
        }
        // Первая публикация после пересоздания топика может ещё отказать: инвариант не зависит от
        // числа попыток - счётчик равен числу сохранённых неудачных попыток outbox.
        await().atMost(KafkaFixture.WAIT)
                .untilAsserted(
                        () -> {
                            deliverPending();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM bank_kafka_outbox WHERE topic=? AND status='PUBLISHED'",
                                                    Integer.class,
                                                    receipts))
                                    .isEqualTo(1);
                        });
        int attempts =
                jdbc.queryForObject(
                        "SELECT attempts FROM bank_kafka_outbox WHERE topic=?",
                        Integer.class,
                        receipts);
        assertThat(failures(LOGIN, RECEIPT_PUBLISH_FAILED) - failed).isEqualTo(attempts);
        assertThat(KafkaFixture.endOffset(receipts)).isEqualTo(1);
        assertThat(
                        mapper.readValue(
                                KafkaFixture.records(receipts, 1).getFirst().value(),
                                NotificationReceipt.class))
                .isEqualTo(new NotificationReceipt(NotificationSource.CASH, event));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_kafka_outbox", Integer.class))
                .isEqualTo(2);
        assertThat(output.getAll())
                .doesNotContain(LOGIN, event.ownerId().toString(), event.eventId().toString());
    }

    private double failures(String login, String reason) {
        Counter counter =
                meters.find("bank.notification.failures")
                        .tags("login", login, "reason", reason)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    private double factGaps() {
        Counter counter = meters.find("bank.notification.failure.fact.gaps").counter();
        return counter == null ? 0 : counter.count();
    }

    private List<BusinessFailureEvent> facts() {
        List<String> payloads =
                jdbc.queryForList(
                        "SELECT payload::text FROM bank_kafka_outbox WHERE topic=?",
                        String.class,
                        BankTopics.BUSINESS_FAILURES);
        return payloads.stream()
                .map(json -> codec.decode(json, BusinessFailureEvent.class))
                .toList();
    }

    // Пауза повтора outbox пропускается: тест не ждёт DeliveryPolicy.RETRY_DELAY_SECONDS.
    private void deliverPending() {
        jdbc.update("UPDATE bank_kafka_outbox SET next_attempt_at=now() WHERE status='PENDING'");
        outbox.pendingIds().forEach(outbox::recover);
    }

    private double producerErrors() {
        return kafka.metrics().values().stream()
                .filter(metric -> metric.metricName().name().equals("record-error-total"))
                .mapToDouble(metric -> ((Number) metric.metricValue()).doubleValue())
                .sum();
    }

    private BankEvent event(String type) {
        return new BankEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                type,
                100,
                500,
                Instant.parse("2026-10-17T12:00:00Z"),
                LOGIN);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class);
    }

    private void send(NotificationSource source, BankEvent event) throws Exception {
        kafka.send(source.getTopic(), event.ownerId().toString(), mapper.writeValueAsString(event))
                .get(5, TimeUnit.SECONDS);
    }

    private void awaitCommitted(NotificationSource source) {
        long end = KafkaFixture.endOffset(source.getTopic());
        await().atMost(KafkaFixture.WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                KafkaFixture.committed(
                                                        BankTopics.NOTIFICATIONS_GROUP,
                                                        source.getTopic()))
                                        .isGreaterThanOrEqualTo(end));
    }
}
