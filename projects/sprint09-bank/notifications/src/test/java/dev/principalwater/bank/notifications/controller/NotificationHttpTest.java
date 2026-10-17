package dev.principalwater.bank.notifications.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
