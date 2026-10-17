package dev.principalwater.bank.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.principalwater.bank.analytics.controller.AnalyticsController;
import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.analytics.dao.ClickHouseWarehouse;
import dev.principalwater.bank.analytics.model.AnalyticsView;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.contract.OwnerReference;
import dev.principalwater.bank.runtime.client.ServiceClient;
import dev.principalwater.bank.testing.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.config.import=classpath:bank-common.properties,classpath:bank-service-client.properties",
            "spring.cloud.zookeeper.enabled=false",
            "spring.cloud.zookeeper.config.enabled=false",
            "spring.cloud.zookeeper.discovery.enabled=false",
            "bank.delivery.enabled=false",
            "bank.analytics.delivery-enabled=false",
            "spring.security.oauth2.client.registration.service.client-secret=fixture-secret",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.cassandra.CassandraRepositoriesAutoConfiguration"
        })
@AutoConfigureMockMvc
@Import(AnalyticsIntegrationTest.FixedTime.class)
class AnalyticsIntegrationTest extends PostgresTestBase {
    private static final Instant NOW = Instant.parse("2026-10-17T01:02:03.123456789Z");
    private static final UUID ALICE = UUID.fromString("ee486035-62dd-41d8-af0c-382f60d1c068");
    private static final UUID BOB = UUID.fromString("e89ee362-5ca9-4064-a97b-24abe2fa4109");
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private CassandraActivityStore cassandra;
    @MockitoBean private ClickHouseWarehouse warehouse;
    @MockitoBean private ServiceClient accounts;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE analytics_inbox");
        when(accounts.post(
                        "accounts",
                        "/internal/accounts/identity",
                        new Actor(JWT.issuer(), "alice-id", "alice"),
                        OwnerReference.class))
                .thenReturn(new OwnerReference(ALICE));
        when(accounts.post(
                        "accounts",
                        "/internal/accounts/identity",
                        new Actor(JWT.issuer(), "bob-id", "bob"),
                        OwnerReference.class))
                .thenReturn(new OwnerReference(BOB));
    }

    @Test
    void inboxAcknowledgesOnlyDurableImmutableEventsAndPreservesNanosecondPayloadOnReplay()
            throws Exception {
        BankEvent event = event();
        String caller =
                "Bearer " + JWT.service("accounts-service", "analytics-api", "analytics.write");
        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(
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
        mvc.perform(
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
            mvc.perform(
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
            mvc.perform(
                            post("/api/analytics/views")
                                    .header(AUTHORIZATION, alice)
                                    .contentType(APPLICATION_JSON)
                                    .content(view))
                    .andExpect(status().isNoContent());
        }
        assertEquals(1, count());
        assertEquals(
                ALICE, jdbc.queryForObject("SELECT owner_id FROM analytics_inbox", UUID.class));
        mvc.perform(
                        post("/api/analytics/views")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer " + JWT.user("bob-id", "bob", "analytics.events"))
                                .contentType(APPLICATION_JSON)
                                .content(view))
                .andExpect(status().isConflict());
        mvc.perform(
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
            mvc.perform(
                            post("/internal/analytics/events")
                                    .header(AUTHORIZATION, "Bearer " + token)
                                    .contentType(APPLICATION_JSON)
                                    .content(event))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(
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
        mvc.perform(
                        get("/api/analytics/business")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.user("alice-id", "alice", "analytics.read")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/analytics/business")
                                .header(
                                        AUTHORIZATION,
                                        "Bearer "
                                                + JWT.analyst(
                                                        "alice-id", "alice", "analytics.events")))
                .andExpect(status().isForbidden());
        when(warehouse.month(YearMonth.of(2026, 10)))
                .thenReturn(new AnalyticsView(true, List.of(), 0L, 0L, 0L, null));
        mvc.perform(
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
        mvc.perform(
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
        mvc.perform(
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
        mvc.perform(get("/api/analytics").header(AUTHORIZATION, alice))
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock analyticsTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
