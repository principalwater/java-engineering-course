package dev.principalwater.bank.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.principalwater.bank.analytics.controller.AnalyticsController;
import dev.principalwater.bank.analytics.dao.AnalyticsInbox;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.BankEvent;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

class AnalyticsIntegrationTest extends AnalyticsTestBase {
    @Autowired private AnalyticsInbox inbox;

    @Test
    void freshFactsPrecedeDueRetriesAndSuccessfulRepublishBackoffIsBounded() {
        BankEvent retry = event();
        inbox.acceptFinancial(retry);
        inbox.release(inbox.claim(retry.eventId()), false);
        jdbc.update(
                "UPDATE analytics_inbox SET next_attempt_at = now() - interval '1 day' WHERE event_id = ?",
                retry.eventId());
        BankEvent fresh = event();
        inbox.acceptFinancial(fresh);
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
    void inboxAcknowledgesOnlyDurableImmutableEventsAndPreservesNanosecondPayloadOnReplay()
            throws Exception {
        BankEvent event = event();
        String caller =
                "Bearer " + JWT.service("accounts-service", "analytics-api", "analytics.write");
        for (int replay = 0; replay < 2; replay++) {
            http.perform(
                            post("/internal/analytics/events")
                                    .header(AUTHORIZATION, caller)
                                    .contentType(APPLICATION_JSON)
                                    .content(mapper.writeValueAsBytes(event)))
                    .andExpect(status().isNoContent());
        }
        assertEquals(1, count());
        assertEquals(
                event,
                mapper.readValue(
                        jdbc.queryForObject(
                                "SELECT payload::text FROM analytics_inbox", String.class),
                        BankEvent.class));
        BankEvent changed = new BankEvent(event.eventId(), ALICE, "DEPOSIT", 30, 100, NOW);
        http.perform(
                        post("/internal/analytics/events")
                                .header(AUTHORIZATION, caller)
                                .contentType(APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(changed)))
                .andExpect(status().isConflict());
        assertFalse(
                jdbc.queryForObject(
                        "SELECT cassandra_done OR warehouse_done FROM analytics_inbox",
                        Boolean.class));
        jdbc.execute("TRUNCATE analytics_inbox");
        jdbc.execute(
                "ALTER TABLE analytics_inbox ADD CONSTRAINT reject_fixture CHECK (event_type <> 'DEPOSIT')");
        try {
            http.perform(
                            post("/internal/analytics/events")
                                    .header(AUTHORIZATION, caller)
                                    .contentType(APPLICATION_JSON)
                                    .content(mapper.writeValueAsBytes(event)))
                    .andExpect(status().isServiceUnavailable());
            assertEquals(0, count());
        } finally {
            jdbc.execute("ALTER TABLE analytics_inbox DROP CONSTRAINT reject_fixture");
        }
    }

    @Test
    void userViewsBindToVerifiedIdentityAndDuplicateEventIdsCannotBeReassigned() throws Exception {
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
                .andExpect(status().isConflict());
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
        assertEquals(1, count());
    }

    @Test
    void signedAccessTokensEnforceCallerAudienceScopeAndActualRealmAnalystRole() throws Exception {
        byte[] event = mapper.writeValueAsBytes(event());
        for (String token :
                List.of(
                        JWT.service("cash-service", "analytics-api", "analytics.write"),
                        JWT.service("accounts-service", "analytics-api", "accounts.write"),
                        JWT.user("alice-id", "alice", "analytics.write"))) {
            http.perform(
                            post("/internal/analytics/events")
                                    .header(AUTHORIZATION, "Bearer " + token)
                                    .contentType(APPLICATION_JSON)
                                    .content(event))
                    .andExpect(status().isForbidden());
        }
        http.perform(
                        post("/internal/analytics/events")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.service(
                                                        "accounts-service",
                                                        "accounts-api",
                                                        "analytics.write"))
                                .contentType(APPLICATION_JSON)
                                .content(event))
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

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("malformedMinorFields")
    void financialWireRejectsCoercionAndMissingRequiredMinorFields(String field, String kind)
            throws Exception {
        ObjectNode body = mapper.valueToTree(event());
        switch (kind) {
            case "string" -> body.put(field, body.get(field).asText());
            case "null" -> body.putNull(field);
            case "missing", "exponent" -> body.remove(field);
            default -> throw new IllegalArgumentException("Unknown wire fixture");
        }
        String json = mapper.writeValueAsString(body);
        if (kind.equals("exponent")) {
            json = json.substring(0, json.length() - 1) + ",\"" + field + "\":1e2}";
        }
        http.perform(
                        post("/internal/analytics/events")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.service(
                                                        "accounts-service",
                                                        "analytics-api",
                                                        "analytics.write"))
                                .contentType(APPLICATION_JSON)
                                .content(json))
                .andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    static Stream<Arguments> malformedMinorFields() {
        return Stream.of("amountMinor", "balanceMinor")
                .flatMap(
                        field ->
                                Stream.of("string", "null", "missing", "exponent")
                                        .map(kind -> Arguments.of(field, kind)));
    }

    @Test
    void personalReadUsesOnlyResolvedOwnerAndCurrentUtcMonthAndNeverFakesPendingProjection()
            throws Exception {
        when(cassandra.month(ALICE, YearMonth.of(2026, 10))).thenReturn(List.of());
        String alice = "Bearer " + JWT.user("alice-id", "alice", "analytics.read");
        http.perform(
                        get("/api/analytics")
                                .queryParam("ownerId", BOB.toString())
                                .queryParam("month", "1970-01")
                                .header(AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.operationsCount").value(0))
                .andExpect(jsonPath("$.updatedAt").hasJsonPath())
                .andExpect(jsonPath("$.updatedAt").isEmpty())
                .andExpect(jsonPath("$.accountsCount").doesNotHaveJsonPath());
        verify(cassandra).month(ALICE, YearMonth.of(2026, 10));
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

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM analytics_inbox", Integer.class);
    }

    private BankEvent event() {
        return new BankEvent(UUID.randomUUID(), ALICE, "DEPOSIT", 29, 100, NOW);
    }
}
