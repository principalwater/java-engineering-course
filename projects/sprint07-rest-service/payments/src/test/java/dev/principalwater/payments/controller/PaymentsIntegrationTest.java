package dev.principalwater.payments.controller;

import dev.principalwater.payments.PaymentTestBase;
import dev.principalwater.payments.generated.model.BalanceResponse;
import dev.principalwater.payments.generated.model.DebitRequest;
import dev.principalwater.payments.generated.model.PaymentReceipt;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class PaymentsIntegrationTest extends PaymentTestBase {
    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    @Autowired private TransactionalOperator transactions;

    @Test
    void unknownBalanceIsReadOnlyAndHealthDoesNotRequireAServiceCredential() {
        UUID account = UUID.randomUUID();
        BalanceResponse balance = balance(account);
        assertEquals(account, balance.getAccountId());
        assertEquals(1000, balance.getBalanceMinor());
        assertEquals(BalanceResponse.CurrencyEnum.RUB, balance.getCurrency());
        assertEquals(0, count("payment_wallets"));
        assertEquals(0, count("payment_decisions"));
        assertEquals(0, count("payment_journal"));
        web.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void replayReturnsTheOriginalReceiptEvenAfterTheWalletChangesAgain() {
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        DebitRequest first = request(account, 250);
        var receipt = pay(key, first, HttpStatus.CREATED);
        pay(UUID.randomUUID(), request(account, 100), HttpStatus.CREATED);
        assertEquals(receipt, pay(key, first, HttpStatus.OK));
        assertEquals(750, receipt.getBalanceMinor());
        assertEquals(650, balance(account).getBalanceMinor());
        assertLedger(account, 650, 2);
    }

    @Test
    void declinedDecisionIsPermanentAndCannotAcquireAnotherKeyForItsReference() {
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        DebitRequest request = request(account, 1200);
        PaymentReceipt declined = pay(key, request, HttpStatus.PAYMENT_REQUIRED);
        assertEquals(PaymentReceipt.StatusEnum.DECLINED, declined.getStatus());
        assertEquals(1000, declined.getBalanceMinor());
        assertEquals(declined, pay(key, request, HttpStatus.PAYMENT_REQUIRED));
        post(UUID.randomUUID(), request).expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertEquals(1, count("payment_decisions"));
        assertLedger(account, 1000, 0);
    }

    @Test
    void concurrentCopiesOfOneKeyCommitOneDebitAndTheSameReceipt() throws Exception {
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        DebitRequest request = request(account, 250);
        var responses = race(() -> response(key, request), () -> response(key, request));
        assertEquals(List.of(200, 201), responses.stream().map(result -> result.getStatus().value()).sorted().toList());
        assertEquals(responses.getFirst().getResponseBody(), responses.getLast().getResponseBody());
        assertEquals(1, count("payment_decisions"));
        assertLedger(account, 750, 1);
    }

    @Test
    void concurrentDistinctDebitsCannotOverdrawTheSameWallet() throws Exception {
        UUID account = UUID.randomUUID();
        var responses = race(() -> response(UUID.randomUUID(), request(account, 700)),
                () -> response(UUID.randomUUID(), request(account, 700)));
        assertEquals(List.of(201, 402), responses.stream().map(result -> result.getStatus().value()).sorted().toList());
        assertEquals(2, count("payment_decisions"));
        assertEquals(300, balance(account).getBalanceMinor());
        assertLedger(account, 300, 1);
    }

    @ParameterizedTest
    @MethodSource("conflictingRequests")
    void aKeyCannotChangeItsAccountReferenceOrAmount(String field) {
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        DebitRequest original = request(account, 250);
        PaymentReceipt receipt = pay(key, original, HttpStatus.CREATED);
        DebitRequest changed = new DebitRequest(original.getAccountId(), original.getReferenceId(), original.getAmountMinor(), original.getCurrency());
        switch (field) {
            case "account" -> changed.setAccountId(UUID.randomUUID());
            case "reference" -> changed.setReferenceId(UUID.randomUUID());
            case "amount" -> changed.setAmountMinor(251L);
            default -> throw new AssertionError("Unknown conflict fixture");
        }
        post(key, changed).expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.status").isEqualTo(409);
        assertEquals(receipt, pay(key, original, HttpStatus.OK));
        assertEquals(1, count("payment_wallets"));
        assertLedger(account, 750, 1);
    }

    @Test
    void rejectedJournalWriteRollsBackTheWalletDecisionAndOpeningFunds() {
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        DebitRequest request = request(account, 700);
        execute("ALTER TABLE payment_journal ADD CONSTRAINT reject_debit_test CHECK (kind <> 'DEBIT')");
        try {
            post(key, request).expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.detail").value(detail -> assertTrue(detail.toString().contains("same idempotency key")));
            assertEquals(0, count("payment_wallets"));
            assertEquals(0, count("payment_decisions"));
            assertEquals(0, count("payment_journal"));
        } finally {
            execute("ALTER TABLE payment_journal DROP CONSTRAINT reject_debit_test");
        }
        pay(key, request, HttpStatus.CREATED);
        assertLedger(account, 300, 1);
    }

    @Test
    void sqlRejectsAnUnbalancedPairAndTheWalletProjectionRemainsUnchanged() {
        UUID account = UUID.randomUUID();
        pay(UUID.randomUUID(), request(account, 100), HttpStatus.CREATED);
        var invalid = database.sql("UPDATE payment_journal SET negative_minor = -1 WHERE kind = 'OPENING'")
                .fetch().rowsUpdated();
        StepVerifier.create(invalid).expectError(DataIntegrityViolationException.class).verify(TIMEOUT);
        var wrongOpening = database.sql("UPDATE payment_journal SET positive_minor = 1, negative_minor = -1, opening_minor = 1 WHERE kind = 'OPENING'")
                .fetch().rowsUpdated();
        StepVerifier.create(wrongOpening).expectError(DataIntegrityViolationException.class).verify(TIMEOUT);
        assertLedger(account, 900, 1);
    }

    @ParameterizedTest
    @MethodSource("invalidJournalReferences")
    void wrongJournalDecisionReferenceRollsBackEveryFinancialWrite(String mismatch) {
        UUID account = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        pay(UUID.randomUUID(), request(account, 100), HttpStatus.CREATED);
        pay(UUID.randomUUID(), request(other, 100), HttpStatus.CREATED);
        UUID key = UUID.randomUUID();
        String decisionStatus = "declined".equals(mismatch) ? "DECLINED" : "SUCCEEDED";
        UUID journalAccount = "account".equals(mismatch) ? other : account;
        long journalAmount = "amount".equals(mismatch) ? 101 : 100;
        var operation = database.sql("""
                INSERT INTO payment_decisions(payment_id, account_id, reference_id, amount_minor, currency, status, balance_minor)
                VALUES (:key, :account, :reference, 100, 'RUB', :status, 800)
                """).bind("key", key).bind("account", account).bind("reference", UUID.randomUUID())
                .bind("status", decisionStatus).fetch().rowsUpdated()
                .then(database.sql("UPDATE payment_wallets SET balance_minor = 800 WHERE account_id = :account")
                        .bind("account", account).fetch().rowsUpdated())
                .then(database.sql("""
                        INSERT INTO payment_journal(kind, journal_id, payment_id, account_id, payment_status, positive_account, negative_account,
                                                    positive_minor, negative_minor)
                        VALUES ('DEBIT', :key, :key, :account, 'SUCCEEDED', 'CLEARING', 'CUSTOMER', :amount, :opposite)
                        """).bind("key", key).bind("account", journalAccount).bind("amount", journalAmount)
                        .bind("opposite", -journalAmount).fetch().rowsUpdated())
                .as(transactions::transactional);
        StepVerifier.create(operation).expectError(DataIntegrityViolationException.class).verify(TIMEOUT);
        assertEquals(2, count("payment_decisions"));
        assertEquals(4, count("payment_journal"));
        assertWalletProjection(account, 900);
        assertWalletProjection(other, 900);
        assertConservation();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void malformedAmountsCurrenciesAndUnknownFieldsCannotReachFinancialState(String amount, String extra, String currency) {
        String body = "{\"accountId\":\"" + UUID.randomUUID() + "\",\"referenceId\":\"" + UUID.randomUUID()
                + "\",\"amountMinor\":" + amount + ",\"currency\":\"" + currency + "\"" + extra + "}";
        web.post().uri(PAYMENTS_PATH).headers(headers -> headers.setBearerAuth(TOKEN))
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertEquals(0, count("payment_wallets"));
        assertEquals(0, count("payment_decisions"));
    }

    @Test
    void missingWrongOrAmbiguousCredentialsCannotReadOrDebitAnAccount() {
        String path = "/api/v1/accounts/" + UUID.randomUUID() + "/balance";
        for (String credential : List.of("", "Basic ignored", "Bearer wrong-service-credential-1234567890")) {
            web.get().uri(path).header(HttpHeaders.AUTHORIZATION, credential).exchange().expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .expectBody().jsonPath("$.status").isEqualTo(401);
        }
        web.post().uri(PAYMENTS_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN, "Bearer " + TOKEN)
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(UUID.randomUUID(), 100)).exchange().expectStatus().isUnauthorized();
        web.post().uri(PAYMENTS_PATH).headers(headers -> headers.setBearerAuth(TOKEN)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(UUID.randomUUID(), 100)).exchange().expectStatus().isBadRequest();
        assertEquals(0, count("payment_wallets"));
    }

    static Stream<String> conflictingRequests() {
        return Stream.of("account", "reference", "amount");
    }

    static Stream<String> invalidJournalReferences() {
        return Stream.of("amount", "account", "declined");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("1.5", "", "RUB"), Arguments.of("1.0", "", "RUB"),
                Arguments.of("\"100\"", "", "RUB"), Arguments.of("9223372036854775808", "", "RUB"),
                Arguments.of("1000000000000001", "", "RUB"), Arguments.of("0", "", "RUB"),
                Arguments.of("-1", "", "RUB"), Arguments.of("null", "", "RUB"),
                Arguments.of("100", ",\"unknown\":true", "RUB"), Arguments.of("100", "", "USD"),
                Arguments.of("100", ",\"amountMinor\":1", "RUB"));
    }

    private WebTestClient.ResponseSpec post(UUID key, DebitRequest request) {
        return web.post().uri(PAYMENTS_PATH).headers(headers -> headers.setBearerAuth(TOKEN))
                .header(IDEMPOTENCY_HEADER, key.toString()).contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();
    }

    private PaymentReceipt pay(UUID key, DebitRequest request, HttpStatus status) {
        return post(key, request).expectStatus().isEqualTo(status).expectBody(PaymentReceipt.class).returnResult().getResponseBody();
    }

    private EntityExchangeResult<PaymentReceipt> response(UUID key, DebitRequest request) {
        return post(key, request).expectBody(PaymentReceipt.class).returnResult();
    }

    private BalanceResponse balance(UUID account) {
        return web.get().uri("/api/v1/accounts/" + account + "/balance").headers(headers -> headers.setBearerAuth(TOKEN))
                .exchange().expectStatus().isOk().expectBody(BalanceResponse.class).returnResult().getResponseBody();
    }

    private DebitRequest request(UUID account, long amount) {
        return new DebitRequest(account, UUID.randomUUID(), amount, DebitRequest.CurrencyEnum.RUB);
    }

    private void assertLedger(UUID account, long expectedBalance, int debits) {
        assertEquals((debits + 1) * 2, count("payment_journal_entries"));
        assertConservation();
        assertWalletProjection(account, expectedBalance);
    }

    private void assertConservation() {
        assertEquals(0, await(database.sql("SELECT SUM(amount_minor) AS total FROM payment_journal_entries")
                .map(row -> ((BigDecimal) row.get("total")).longValueExact()).one()));
    }

    private void assertWalletProjection(UUID account, long expectedBalance) {
        assertEquals(expectedBalance, await(database.sql("""
                SELECT SUM(amount_minor) AS total FROM payment_journal_entries
                WHERE account_id = :account AND ledger_account = 'CUSTOMER'
                """).bind("account", account).map(row -> ((BigDecimal) row.get("total")).longValueExact()).one()));
        assertEquals(expectedBalance, balance(account).getBalanceMinor());
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = workers.submit(() -> together(first, ready, start));
            var right = workers.submit(() -> together(second, ready, start));
            try {
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                start.countDown();
                return List.of(left.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS), right.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            } finally {
                start.countDown();
                left.cancel(true);
                right.cancel(true);
            }
        }
    }

    private <T> T together(Callable<T> operation, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(3, TimeUnit.SECONDS));
        return operation.call();
    }
}
