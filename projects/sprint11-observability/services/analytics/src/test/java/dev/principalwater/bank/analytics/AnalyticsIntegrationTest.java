package dev.principalwater.bank.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import dev.principalwater.bank.analytics.controller.AnalyticsController;
import dev.principalwater.bank.analytics.dao.ActivityExportQueue;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsProjection;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.analyticsdemo.AnalyticsDemo;
import dev.principalwater.bank.analyticsdemo.AnalyticsDemoApplication;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.UiActivityRequest;
import dev.principalwater.bank.contract.UiActivitySection;
import dev.principalwater.bank.contract.UiActivityType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AnalyticsIntegrationTest extends AnalyticsTestBase {
    @Autowired private AnalyticsInbox inbox;
    @Autowired private ActivityExportQueue exports;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void optInDemoReplaysFourteenCompleteUtcDaysWithoutChangingExistingOwnersOrMonthlyAdmission()
            throws Exception {
        BankEvent existing = event();
        inbox.acceptBackend(existing);
        String original =
                jdbc.queryForObject(
                        "SELECT payload::text FROM analytics_inbox WHERE event_id=?",
                        String.class,
                        existing.eventId());
        AnalyticsDemo wrongRole =
                new AnalyticsDemo(
                        jdbc,
                        mapper,
                        new TransactionTemplate(transactions),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        LocalDate lastDay = LocalDate.of(2026, 10, 16);
        assertThrows(
                IllegalArgumentException.class,
                () -> wrongRole.load(false, "bank11-test", lastDay));
        assertThrows(
                IllegalArgumentException.class, () -> wrongRole.load(true, "bank11-prod", lastDay));
        assertThrows(
                IllegalStateException.class, () -> wrongRole.load(true, "bank11-dev", lastDay));
        assertThrows(
                IllegalArgumentException.class,
                () -> wrongRole.load(true, "bank11-test", lastDay.plusDays(1)));
        assertEquals(1, count());

        MockEnvironment vault =
                new MockEnvironment()
                        .withProperty("spring.cloud.vault.enabled", "true")
                        .withProperty("spring.cloud.vault.authentication", "KUBERNETES")
                        .withProperty("spring.cloud.vault.kubernetes.role", "bank11-dev-analytics")
                        .withProperty("BANK_CONFIG_IMPORT", "vault:///kv/bank11-dev/analytics");
        assertEquals("bank11-dev", AnalyticsDemoApplication.namespace(vault));
        vault.setProperty("BANK_CONFIG_IMPORT", "vault:///kv/bank11-prod/analytics");
        assertThrows(
                IllegalArgumentException.class, () -> AnalyticsDemoApplication.namespace(vault));
        vault.setProperty("BANK_CONFIG_IMPORT", "vault:///kv/bank11-dev/analytics");
        vault.setProperty("spring.cloud.vault.enabled", "false");
        assertThrows(
                IllegalArgumentException.class, () -> AnalyticsDemoApplication.namespace(vault));
        vault.setProperty("spring.cloud.vault.enabled", "true");
        vault.setProperty("spring.cloud.vault.kubernetes.role", "bank11-prod-analytics");
        assertThrows(
                IllegalArgumentException.class, () -> AnalyticsDemoApplication.namespace(vault));

        // Собственный контейнер даёт настоящую роль analytics, без production-исключения для имени
        // тестовой БД.
        jdbc.execute(
                "DO $$ BEGIN CREATE ROLE analytics LOGIN PASSWORD 'demo-fixture-password'; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA analytics TO analytics");
        jdbc.execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA analytics TO analytics");
        DriverManagerDataSource fixture =
                new DriverManagerDataSource(
                        jdbc.getDataSource().unwrap(HikariDataSource.class).getJdbcUrl(),
                        "analytics",
                        "demo-fixture-password");
        Properties connection = new Properties();
        connection.setProperty("currentSchema", "analytics");
        fixture.setConnectionProperties(connection);
        AnalyticsDemo demo =
                new AnalyticsDemo(
                        new JdbcTemplate(fixture),
                        mapper,
                        new TransactionTemplate(new DataSourceTransactionManager(fixture)),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        int generated = demo.load(true, "bank11-test", lastDay);
        assertTrue(generated >= 300 && generated <= 450);
        List<String> stored =
                jdbc.queryForList(
                        "SELECT payload::text FROM analytics_inbox ORDER BY event_id",
                        String.class);
        List<Integer> monthly =
                jdbc.queryForList(
                        "SELECT accepted FROM analytics_ui_months ORDER BY owner_id,month",
                        Integer.class);
        assertEquals(generated, demo.load(true, "bank11-test", lastDay));
        assertEquals(
                stored,
                jdbc.queryForList(
                        "SELECT payload::text FROM analytics_inbox ORDER BY event_id",
                        String.class));
        assertEquals(
                monthly,
                jdbc.queryForList(
                        "SELECT accepted FROM analytics_ui_months ORDER BY owner_id,month",
                        Integer.class));
        assertEquals(generated + 1, count());
        assertEquals(
                original,
                jdbc.queryForObject(
                        "SELECT payload::text FROM analytics_inbox WHERE event_id=?",
                        String.class,
                        existing.eventId()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE owner_id=?",
                        Integer.class,
                        BOB));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(DISTINCT owner_id) FROM analytics_inbox WHERE owner_id<>?",
                        Integer.class,
                        ALICE));
        assertEquals(
                14,
                jdbc.queryForObject(
                        "SELECT count(DISTINCT (occurred_at AT TIME ZONE 'UTC')::date) FROM analytics_inbox WHERE owner_id<>?",
                        Integer.class,
                        ALICE));
        assertEquals(
                LocalDate.of(2026, 10, 3),
                jdbc.queryForObject(
                        "SELECT min((occurred_at AT TIME ZONE 'UTC')::date) FROM analytics_inbox",
                        LocalDate.class));
        assertEquals(
                lastDay,
                jdbc.queryForObject(
                        "SELECT max((occurred_at AT TIME ZONE 'UTC')::date) FROM analytics_inbox WHERE owner_id<>?",
                        LocalDate.class,
                        ALICE));
        assertTrue(
                jdbc.queryForList("SELECT DISTINCT event_type FROM analytics_inbox", String.class)
                        .containsAll(
                                List.of(
                                        "DEPOSIT",
                                        "WITHDRAW",
                                        "NOTIFICATION_AVAILABLE",
                                        "NOTIFICATION_READ",
                                        "SCREEN_OPENED",
                                        "FORM_OPENED",
                                        "PROFILE_UPDATED")));
        assertEquals(
                generated,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE owner_id<>? AND NOT cassandra_done AND NOT warehouse_done",
                        Integer.class,
                        ALICE));
        assertTrue(monthly.stream().allMatch(accepted -> accepted > 0 && accepted < 1000));
        var start = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var end = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertTrue(inbox.warehousePending(start, end));
        jdbc.update(
                "UPDATE analytics_inbox SET warehouse_done=true WHERE event_id=?",
                existing.eventId());
        assertFalse(inbox.warehousePending(start, end));
    }

    @Test
    void freshFactsPrecedeDueRetriesAndSuccessfulRepublishBackoffIsBounded() {
        BankEvent retry = event();
        inbox.acceptBackend(retry);
        inbox.release(inbox.claim(retry.eventId()), false);
        jdbc.update(
                "UPDATE analytics_inbox SET next_attempt_at = now() - interval '1 day' WHERE event_id = ?",
                retry.eventId());
        BankEvent fresh = event();
        inbox.acceptBackend(fresh);
        assertEquals(fresh.eventId(), inbox.pendingIds().getFirst());

        inbox.release(inbox.claim(retry.eventId()), false);
        double secondDelay =
                jdbc.queryForObject(
                        "SELECT extract(epoch FROM next_attempt_at - now()) FROM analytics_inbox WHERE event_id = ?",
                        Double.class,
                        retry.eventId());
        assertTrue(secondDelay > 59 && secondDelay <= 60);
        jdbc.update(
                "UPDATE analytics_inbox SET attempts = 1000000, next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                retry.eventId());
        inbox.release(inbox.claim(retry.eventId()), false);
        double cappedDelay =
                jdbc.queryForObject(
                        "SELECT extract(epoch FROM next_attempt_at - now()) FROM analytics_inbox WHERE event_id = ?",
                        Double.class,
                        retry.eventId());
        assertTrue(cappedDelay > 899 && cappedDelay <= 900);
    }

    @Test
    void userViewsKeepImmutableOwnerNamespacesForReusedClientEventIds() throws Exception {
        UUID eventId = UUID.randomUUID();
        String alice = "Bearer " + JWT.user("alice-id", "alice", "analytics.events");
        byte[] view = mapper.writeValueAsBytes(new AnalyticsController.ViewRequest(eventId));
        for (int replay = 0; replay < 2; replay++) {
            http.perform(
                            post("/api/analytics/views")
                                    .header(AUTHORIZATION, alice)
                                    .contentType(APPLICATION_JSON)
                                    .content(view))
                    .andExpect(status().isNoContent());
        }
        assertEquals(1, count());
        assertEquals(
                ALICE, jdbc.queryForObject("SELECT owner_id FROM analytics_inbox", UUID.class));
        http.perform(
                        post("/api/analytics/views")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer " + JWT.user("bob-id", "bob", "analytics.events"))
                                .contentType(APPLICATION_JSON)
                                .content(view))
                .andExpect(status().isNoContent());
        http.perform(
                        post("/api/analytics/views")
                                .header(AUTHORIZATION, alice)
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"eventId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"ownerId\":\""
                                                + BOB
                                                + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(2, count());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE owner_id = ?",
                        Integer.class,
                        ALICE));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE owner_id = ?",
                        Integer.class,
                        BOB));
    }

    @Test
    void signedAccessTokensEnforceCallerAudienceScopeAndActualRealmAnalystRole() throws Exception {
        for (String token :
                List.of(
                        JWT.service("accounts-service", "analytics-api", "analytics.read"),
                        JWT.user("alice-id", "alice", "analytics.events"))) {
            http.perform(get("/api/analytics").header(AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        http.perform(
                        get("/api/analytics")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.service(
                                                        "accounts-service",
                                                        "accounts-api",
                                                        "analytics.read")))
                .andExpect(status().isUnauthorized());
        http.perform(
                        get("/api/analytics/business")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.user("alice-id", "alice", "analytics.read")))
                .andExpect(status().isForbidden());
        http.perform(
                        get("/api/analytics/business")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.analyst(
                                                        "alice-id", "alice", "analytics.events")))
                .andExpect(status().isForbidden());
        when(warehouse.month(YearMonth.of(2026, 10)))
                .thenReturn(new AnalyticsView(true, List.of(), 0L, 0L, 0L, null));
        http.perform(
                        get("/api/analytics/business")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.analyst(
                                                        "alice-id", "alice", "analytics.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
        assertEquals(0, count());
    }

    @Test
    void personalReadUsesOnlyResolvedOwnerAndBoundedSelectedUtcMonthAndNeverFakesPendingProjection()
            throws Exception {
        when(cassandra.month(ALICE, YearMonth.of(2026, 9))).thenReturn(List.of());
        String alice = "Bearer " + JWT.user("alice-id", "alice", "analytics.read");
        http.perform(
                        get("/api/analytics")
                                .queryParam("ownerId", BOB.toString())
                                .queryParam("month", "2026-09")
                                .header(AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.operationsCount").value(0))
                .andExpect(jsonPath("$.updatedAt").hasJsonPath())
                .andExpect(jsonPath("$.updatedAt").isEmpty())
                .andExpect(jsonPath("$.accountsCount").doesNotHaveJsonPath());
        verify(cassandra).month(ALICE, YearMonth.of(2026, 9));
        for (String invalid : List.of("2026-06", "2026-11", "1970-01", "bad", "2026-99")) {
            http.perform(
                            get("/api/analytics")
                                    .queryParam("month", invalid)
                                    .header(AUTHORIZATION, alice))
                    .andExpect(status().isBadRequest());
        }
        clearInvocations(cassandra);
        jdbc.update(
                """
                INSERT INTO analytics_inbox(event_id,owner_id,event_type,occurred_at,payload,accepted_at)
                VALUES (?,?,'DEPOSIT',?,'{}'::jsonb,?)
                """,
                UUID.randomUUID(),
                ALICE,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        http.perform(get("/api/analytics").header(AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.operationsCount").isEmpty());
        verifyNoInteractions(cassandra);
    }

    @Test
    void typedUiActivitiesDeriveOwnerAndFirstTimestampAndRejectOwnPayloadReassignment()
            throws Exception {
        UUID eventId = UUID.randomUUID();
        String alice = "Bearer " + JWT.user("alice-id", "alice", "analytics.events");
        byte[] body =
                mapper.writeValueAsBytes(
                        new UiActivityRequest(
                                eventId,
                                UiActivityType.SCREEN_OPENED,
                                UiActivitySection.ANALYTICS));
        for (int replay = 0; replay < 2; replay++)
            http.perform(
                            post("/api/analytics/activities")
                                    .header(AUTHORIZATION, alice)
                                    .contentType(APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isNoContent());
        assertEquals(1, count());
        assertEquals(
                ALICE, jdbc.queryForObject("SELECT owner_id FROM analytics_inbox", UUID.class));
        assertEquals(
                NOW.toString(),
                mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT payload::text FROM analytics_inbox", String.class))
                        .path("occurredAt")
                        .asText());
        http.perform(
                        post("/api/analytics/activities")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer " + JWT.user("bob-id", "bob", "analytics.events"))
                                .contentType(APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNoContent());
        http.perform(
                        post("/api/analytics/activities")
                                .header(AUTHORIZATION, alice)
                                .contentType(APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsBytes(
                                                new UiActivityRequest(
                                                        eventId,
                                                        UiActivityType.SCREEN_OPENED,
                                                        UiActivitySection.HISTORY))))
                .andExpect(status().isConflict());
        for (String json :
                List.of(
                        "{\"eventId\":\""
                                + UUID.randomUUID()
                                + "\",\"type\":\"FORM_OPENED\",\"section\":\"NOTIFICATIONS\"}",
                        "{\"eventId\":\""
                                + UUID.randomUUID()
                                + "\",\"type\":\"SCREEN_OPENED\",\"section\":\"ANALYTICS\",\"ownerId\":\""
                                + BOB
                                + "\"}",
                        "{\"eventId\":\""
                                + UUID.randomUUID()
                                + "\",\"type\":\"SCREEN_OPENED\",\"section\":\"ANALYTICS\",\"amountMinor\":29}")) {
            http.perform(
                            post("/api/analytics/activities")
                                    .header(AUTHORIZATION, alice)
                                    .contentType(APPLICATION_JSON)
                                    .content(json))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(2, count());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE owner_id = ?",
                        Integer.class,
                        ALICE));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE owner_id = ?",
                        Integer.class,
                        BOB));
    }

    @Test
    void monthlyUiAdmissionIsOwnerScopedAndFullPartitionStillAcceptsImmutableReplay()
            throws Exception {
        jdbc.update(
                "INSERT INTO analytics_ui_months(owner_id, month, accepted) VALUES (?, '2026-10', 998)",
                ALICE);
        UUID eventId = UUID.randomUUID();
        String alice = "Bearer " + JWT.user("alice-id", "alice", "analytics.events");
        byte[] legacyView =
                mapper.writeValueAsBytes(new AnalyticsController.ViewRequest(UUID.randomUUID()));
        for (int replay = 0; replay < 2; replay++)
            http.perform(
                            post("/api/analytics/views")
                                    .header(AUTHORIZATION, alice)
                                    .contentType(APPLICATION_JSON)
                                    .content(legacyView))
                    .andExpect(status().isNoContent());
        byte[] body =
                mapper.writeValueAsBytes(
                        new UiActivityRequest(
                                eventId,
                                UiActivityType.SCREEN_OPENED,
                                UiActivitySection.ANALYTICS));
        for (int replay = 0; replay < 2; replay++)
            http.perform(
                            post("/api/analytics/activities")
                                    .header(AUTHORIZATION, alice)
                                    .contentType(APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isNoContent());
        assertEquals(
                1000,
                jdbc.queryForObject(
                        "SELECT accepted FROM analytics_ui_months WHERE owner_id = ?",
                        Integer.class,
                        ALICE));
        byte[] otherAction =
                mapper.writeValueAsBytes(
                        new UiActivityRequest(
                                UUID.randomUUID(),
                                UiActivityType.FORM_OPENED,
                                UiActivitySection.PROFILE));
        http.perform(
                        post("/api/analytics/activities")
                                .header(AUTHORIZATION, alice)
                                .contentType(APPLICATION_JSON)
                                .content(otherAction))
                .andExpect(status().isTooManyRequests());
        http.perform(
                        post("/api/analytics/views")
                                .header(AUTHORIZATION, alice)
                                .contentType(APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsBytes(
                                                new AnalyticsController.ViewRequest(
                                                        UUID.randomUUID()))))
                .andExpect(status().isTooManyRequests());
        assertEquals(2, count());
        http.perform(
                        post("/api/analytics/activities")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer " + JWT.user("bob-id", "bob", "analytics.events"))
                                .contentType(APPLICATION_JSON)
                                .content(otherAction))
                .andExpect(status().isNoContent());
        assertEquals(3, count());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT accepted FROM analytics_ui_months WHERE owner_id = ?",
                        Integer.class,
                        BOB));
    }

    @Test
    void staleCassandraSnapshotRevisionCannotAcknowledgeNewerUiDelivery() {
        UiActivityRequest first =
                new UiActivityRequest(
                        UUID.randomUUID(), UiActivityType.SCREEN_OPENED, UiActivitySection.HISTORY);
        inbox.acceptUi(first, ALICE);
        UUID firstStored = jdbc.queryForObject("SELECT event_id FROM analytics_inbox", UUID.class);
        inbox.acknowledgeProjection(firstStored, AnalyticsProjection.PERSONAL);
        ActivityExportQueue.Export old = exports.claim();
        assertTrue(old != null);
        UiActivityRequest second =
                new UiActivityRequest(
                        UUID.randomUUID(), UiActivityType.FORM_OPENED, UiActivitySection.PROFILE);
        inbox.acceptUi(second, ALICE);
        UUID secondStored =
                jdbc.queryForObject(
                        "SELECT event_id FROM analytics_inbox WHERE event_id <> ?",
                        UUID.class,
                        firstStored);
        inbox.acknowledgeProjection(secondStored, AnalyticsProjection.PERSONAL);
        exports.complete(old);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE warehouse_done",
                        Integer.class));
        ActivityExportQueue.Export current = exports.claim();
        assertTrue(current.revision() > old.revision());
        exports.fail(current);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE warehouse_done",
                        Integer.class));
        jdbc.update("UPDATE analytics_activity_exports SET next_attempt_at = now()");
        ActivityExportQueue.Export retry = exports.claim();
        assertEquals(current.revision(), retry.revision());
        exports.complete(retry);
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_inbox WHERE cassandra_done AND warehouse_done",
                        Integer.class));
        assertTrue(exports.claim() == null);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM analytics_inbox", Integer.class);
    }

    private BankEvent event() {
        return new BankEvent(UUID.randomUUID(), ALICE, "DEPOSIT", 29, 100, NOW);
    }
}
