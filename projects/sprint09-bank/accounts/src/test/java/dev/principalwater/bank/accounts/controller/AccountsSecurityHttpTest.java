package dev.principalwater.bank.accounts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.principalwater.bank.accounts.AccountsTestBase;
import dev.principalwater.bank.accounts.service.MoneyService;
import dev.principalwater.bank.accounts.service.ProfileService;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.contract.OperationType;
import dev.principalwater.bank.testing.BankFixtures;
import dev.principalwater.bank.testing.JwtFixture;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AccountsSecurityHttpTest extends AccountsTestBase {
    @Autowired private MoneyService money;
    @Autowired private ProfileService profiles;

    @Test
    void anonymousCannotProvisionAccount() throws Exception {
        http.perform(get("/api/accounts/me")).andExpect(status().isUnauthorized());
        assertThat(count("account")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void realDecoderRejectsInvalidSignedTokenBeforeAccountAccess(String reason, String token)
            throws Exception {
        http.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertThat(count("account")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthorizedCommands")
    void internalRouteRequiresCallerScopeAudienceAndAllowedOperation(
            String reason, String token, OperationType type) throws Exception {
        InternalOperation command =
                new InternalOperation(
                        BankFixtures.actor("alice"),
                        UUID.randomUUID(),
                        type,
                        100L,
                        type == OperationType.TRANSFER ? UUID.randomUUID() : null);
        http.perform(
                        post("/internal/accounts/operations")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(command)))
                .andExpect(status().isForbidden());
        assertThat(count("money_operation")).isZero();
        assertThat(count("account")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "null", "MISSING"})
    void invalidMoneyJsonIsRejectedBeforeIntentOrBalanceMutation(String value) throws Exception {
        ObjectNode body =
                mapper.valueToTree(BankFixtures.deposit(BankFixtures.actor("alice"), 100));
        if ("MISSING".equals(value)) {
            body.remove("amountMinor");
        } else {
            body.set("amountMinor", mapper.readTree(value));
        }
        http.perform(
                        post("/internal/accounts/operations")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + JWT.service(
                                                        "cash-service",
                                                        "accounts-api",
                                                        "accounts.cash"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_REQUEST"));
        assertThat(count("account")).isZero();
        assertThat(count("money_operation")).isZero();
    }

    @Test
    void userProfileAndHistoryStayOwnedAndRecipientProjectionHasNoBalance() throws Exception {
        Actor alice = ready("alice");
        Actor bob = ready("bob");
        UUID aliceId = profiles.own(alice).accountId();
        UUID bobId = profiles.own(bob).accountId();
        money.execute(BankFixtures.deposit(alice, 111));
        money.execute(BankFixtures.deposit(bob, 999));
        String token =
                "Bearer "
                        + JWT.user(alice.subject(), alice.login(), "accounts.read accounts.write");

        http.perform(
                        get("/api/accounts/me")
                                .param("ownerId", bobId.toString())
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("accountId").value(aliceId.toString()))
                .andExpect(jsonPath("balanceMinor").value(111));
        http.perform(get("/api/accounts/" + bobId).header("Authorization", token))
                .andExpect(status().isNotFound());
        http.perform(
                        get("/api/accounts/history")
                                .param("ownerId", bobId.toString())
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("items[0].amountMinor").value(111))
                .andExpect(jsonPath("items[0].balanceMinor").value(111));
        http.perform(get("/api/accounts/recipients").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("items[0].accountId").value(bobId.toString()))
                .andExpect(jsonPath("items[0].balanceMinor").doesNotExist())
                .andExpect(jsonPath("items[0].birthDate").doesNotExist());

        ObjectNode profile = mapper.valueToTree(BankFixtures.profile("Changed Alice"));
        profile.put("version", profiles.own(alice).version());
        profile.put("ownerId", bobId.toString());
        http.perform(
                        put("/api/accounts/me")
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(profile)))
                .andExpect(status().isBadRequest());
        assertThat(profiles.own(bob).fullName()).isEqualTo("bob Example");
        profile.remove("ownerId");
        http.perform(
                        put("/api/accounts/me")
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("accountId").value(aliceId.toString()))
                .andExpect(jsonPath("fullName").value("Changed Alice"));
    }

    static Stream<Arguments> invalidTokens() {
        Instant expires = Instant.now().plusSeconds(300);
        String signature;
        try (JwtFixture other = new JwtFixture()) {
            signature =
                    other.token(
                            "alice-sub",
                            "bank-front",
                            List.of("bank-api"),
                            "accounts.read",
                            JWT.issuer(),
                            expires,
                            "alice");
        }
        return Stream.of(
                Arguments.of("signature", signature),
                Arguments.of(
                        "issuer",
                        JWT.token(
                                "alice-sub",
                                "bank-front",
                                List.of("bank-api"),
                                "accounts.read",
                                "https://other.bank",
                                expires,
                                "alice")),
                Arguments.of(
                        "missing audience",
                        JWT.token(
                                "alice-sub",
                                "bank-front",
                                null,
                                "accounts.read",
                                JWT.issuer(),
                                expires,
                                "alice")),
                Arguments.of(
                        "audience",
                        JWT.token(
                                "alice-sub",
                                "bank-front",
                                List.of("foreign-api"),
                                "accounts.read",
                                JWT.issuer(),
                                expires,
                                "alice")),
                Arguments.of(
                        "expired",
                        JWT.token(
                                "alice-sub",
                                "bank-front",
                                List.of("bank-api"),
                                "accounts.read",
                                JWT.issuer(),
                                Instant.now().minusSeconds(120),
                                "alice")));
    }

    static Stream<Arguments> unauthorizedCommands() {
        return Stream.of(
                Arguments.of(
                        "human token",
                        JWT.token(
                                "alice-sub",
                                "bank-front",
                                List.of("accounts-api"),
                                "accounts.cash",
                                JWT.issuer(),
                                Instant.now().plusSeconds(300),
                                "alice"),
                        OperationType.DEPOSIT),
                Arguments.of(
                        "wrong caller",
                        JWT.service("notifications-service", "accounts-api", "accounts.cash"),
                        OperationType.DEPOSIT),
                Arguments.of(
                        "wrong scope",
                        JWT.service("cash-service", "accounts-api", "accounts.read"),
                        OperationType.DEPOSIT),
                Arguments.of(
                        "wrong service audience",
                        JWT.service("cash-service", "bank-api", "accounts.cash"),
                        OperationType.DEPOSIT),
                Arguments.of(
                        "cash cannot transfer",
                        JWT.service("cash-service", "accounts-api", "accounts.cash"),
                        OperationType.TRANSFER),
                Arguments.of(
                        "transfer cannot deposit",
                        JWT.service("transfer-service", "accounts-api", "accounts.transfer"),
                        OperationType.DEPOSIT));
    }

    private Actor ready(String login) {
        Actor actor = new Actor(JWT.issuer(), login + "-sub", login);
        profiles.update(actor, BankFixtures.profile(login + " Example"));
        return actor;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
