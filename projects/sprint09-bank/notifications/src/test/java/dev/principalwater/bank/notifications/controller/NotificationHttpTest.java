package dev.principalwater.bank.notifications.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.principalwater.bank.contract.BankEvent;
import dev.principalwater.bank.notifications.NotificationsTestBase;
import dev.principalwater.bank.runtime.client.ServiceClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

@ExtendWith(OutputCaptureExtension.class)
class NotificationHttpTest extends NotificationsTestBase {
    @Autowired private ApplicationContext context;

    @Test
    void receiptIsDurableDeduplicatedPerCallerAndRejectsChangedPayload(CapturedOutput output)
            throws Exception {
        BankEvent event = event(UUID.randomUUID(), 400);
        String caller = JWT.service("cash-service", "notifications-api", "notifications.write");
        send(caller, event).andExpect(status().isNoContent());
        send(caller, event).andExpect(status().isNoContent());
        send(
                        caller,
                        new BankEvent(
                                event.eventId(),
                                event.ownerId(),
                                event.type(),
                                401,
                                event.balanceMinor(),
                                event.occurredAt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("OPERATION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'amountMinor' FROM bank_notification",
                                String.class))
                .isEqualTo("400");
        assertThat(actions(output)).containsExactly("Notification action=DEPOSIT_SUCCEEDED");
        BankEvent transfer =
                new BankEvent(
                        event.eventId(),
                        event.ownerId(),
                        "TRANSFER_SUCCEEDED",
                        event.amountMinor(),
                        event.balanceMinor(),
                        event.occurredAt());
        send(JWT.service("transfer-service", "notifications-api", "notifications.write"), transfer)
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class))
                .isEqualTo(2);
        assertThat(context.getBeansOfType(ServiceClient.class)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "accounts-service,PROFILE_UPDATED",
        "cash-service,DEPOSIT_SUCCEEDED",
        "cash-service,DEPOSIT_REJECTED",
        "cash-service,WITHDRAW_SUCCEEDED",
        "cash-service,WITHDRAW_REJECTED",
        "transfer-service,TRANSFER_SUCCEEDED",
        "transfer-service,TRANSFER_REJECTED"
    })
    void knownActionIsPersistedAndLoggedWithoutPayload(
            String caller, String type, CapturedOutput output) throws Exception {
        BankEvent template = event(UUID.randomUUID(), 400);
        BankEvent event =
                new BankEvent(
                        template.eventId(),
                        template.ownerId(),
                        type,
                        template.amountMinor(),
                        template.balanceMinor(),
                        template.occurredAt());
        send(JWT.service(caller, "notifications-api", "notifications.write"), event)
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class))
                .isEqualTo(1);
        assertThat(actions(output)).containsExactly("Notification action=" + type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_ACTION", "DEPOSIT_SUCCEEDED\nFORGED"})
    void unknownActionCannotBePersistedOrLogged(String type, CapturedOutput output)
            throws Exception {
        BankEvent template = event(UUID.randomUUID(), 400);
        BankEvent event =
                new BankEvent(
                        template.eventId(),
                        template.ownerId(),
                        type,
                        template.amountMinor(),
                        template.balanceMinor(),
                        template.occurredAt());
        send(JWT.service("cash-service", "notifications-api", "notifications.write"), event)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class))
                .isZero();
        assertThat(actions(output)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "accounts-service,DEPOSIT_SUCCEEDED",
        "cash-service,TRANSFER_SUCCEEDED",
        "transfer-service,PROFILE_UPDATED"
    })
    void allowedServiceCannotClaimAnotherCallersAction(
            String caller, String type, CapturedOutput output) throws Exception {
        BankEvent template = event(UUID.randomUUID(), 400);
        BankEvent event =
                new BankEvent(
                        template.eventId(),
                        template.ownerId(),
                        type,
                        template.amountMinor(),
                        template.balanceMinor(),
                        template.occurredAt());
        send(JWT.service(caller, "notifications-api", "notifications.write"), event)
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class))
                .isZero();
        assertThat(actions(output)).isEmpty();
    }

    @Test
    void incomingGuardRequiresAllowedServiceCallerAudienceAndScopeWithoutPersistingRejectedEvents()
            throws Exception {
        BankEvent event = event(UUID.randomUUID(), 400);
        send(JWT.service("untrusted-service", "notifications-api", "notifications.write"), event)
                .andExpect(status().isForbidden());
        send(JWT.service("cash-service", "notifications-api", "accounts.cash"), event)
                .andExpect(status().isForbidden());
        send(JWT.service("cash-service", "bank-api", "notifications.write"), event)
                .andExpect(status().isForbidden());
        send(JWT.user("alice", "alice", "notifications.write"), event)
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_notification", Integer.class))
                .isZero();
    }

    @Test
    void inboxReadsPersistedEventsForOneOwnerWithStableCursorAndNoDuplicates() throws Exception {
        UUID owner = UUID.randomUUID();
        String producer = JWT.service("cash-service", "notifications-api", "notifications.write");
        BankEvent first =
                new BankEvent(
                        UUID.randomUUID(),
                        owner,
                        "DEPOSIT_SUCCEEDED",
                        100,
                        100,
                        Instant.parse("2026-10-17T12:00:00Z"));
        BankEvent last =
                new BankEvent(
                        UUID.randomUUID(), owner, "WITHDRAW_SUCCEEDED", 25, 75, first.occurredAt());
        send(producer, first).andExpect(status().isNoContent());
        send(producer, event(UUID.randomUUID(), 999)).andExpect(status().isNoContent());
        send(producer, last).andExpect(status().isNoContent());
        send(producer, last).andExpect(status().isNoContent());
        String reader =
                "Bearer "
                        + JWT.service(
                                "accounts-service", "notifications-api", "notifications.write");
        var response =
                http.perform(
                                get("/internal/notifications/" + owner)
                                        .param("limit", "1")
                                        .header("Authorization", reader))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("items.length()").value(1))
                        .andExpect(jsonPath("items[0].eventId").value(last.eventId().toString()))
                        .andExpect(jsonPath("items[0].balanceMinor").value(75))
                        .andExpect(jsonPath("items[0].ownerId").doesNotExist())
                        .andReturn();
        String cursor =
                mapper.readTree(response.getResponse().getContentAsByteArray())
                        .path("nextCursor")
                        .asText();
        // Новое событие между страницами не сдвигает уже выданную границу.
        send(
                        producer,
                        new BankEvent(
                                UUID.randomUUID(),
                                owner,
                                "DEPOSIT_REJECTED",
                                200,
                                75,
                                first.occurredAt()))
                .andExpect(status().isNoContent());
        http.perform(
                        get("/internal/notifications/" + owner)
                                .param("before", cursor)
                                .param("limit", "1")
                                .header("Authorization", reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("items[0].eventId").value(first.eventId().toString()))
                .andExpect(jsonPath("nextCursor").isEmpty());
        http.perform(
                        get("/internal/notifications/" + UUID.randomUUID())
                                .header("Authorization", reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items").isEmpty());
        for (String limit : List.of("0", "51")) {
            http.perform(
                            get("/internal/notifications/" + owner)
                                    .param("limit", limit)
                                    .header("Authorization", reader))
                    .andExpect(status().isBadRequest());
        }
        http.perform(
                        get("/internal/notifications/" + owner)
                                .param("before", "0")
                                .header("Authorization", reader))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTrustedAccountsCanReadInboxNotBrowserOrMoneyProducers() throws Exception {
        String path = "/internal/notifications/" + UUID.randomUUID();
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String token :
                List.of(
                        JWT.user("alice", "alice", "notifications.write"),
                        JWT.service("cash-service", "notifications-api", "notifications.write"),
                        JWT.service("accounts-service", "bank-api", "notifications.write"),
                        JWT.service("accounts-service", "notifications-api", "accounts.read"))) {
            http.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    private org.springframework.test.web.servlet.ResultActions send(String token, BankEvent event)
            throws Exception {
        return http.perform(
                post("/internal/notifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(event)));
    }

    private BankEvent event(UUID id, long amount) {
        return new BankEvent(
                id,
                UUID.randomUUID(),
                "DEPOSIT_SUCCEEDED",
                amount,
                400,
                Instant.parse("2026-10-17T12:00:00Z"));
    }

    private List<String> actions(CapturedOutput output) {
        return output.getOut()
                .lines()
                .filter(line -> line.contains("Notification action="))
                .map(line -> line.substring(line.indexOf("Notification action=")))
                .toList();
    }
}
