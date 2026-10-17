package dev.principalwater.payments.config;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.principalwater.payments.PaymentTestBase;
import dev.principalwater.payments.PaymentTokens;
import dev.principalwater.payments.generated.model.DebitRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static dev.principalwater.payments.PaymentTokens.CLIENT;
import static dev.principalwater.payments.PaymentTokens.ISSUER;
import static dev.principalwater.payments.PaymentTokens.READ;
import static dev.principalwater.payments.PaymentTokens.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Реальные декодер, JWK Set по HTTP и цепочка фильтров: токен-заглушка не доказывает ни подпись, ни срок. */
class PaymentsSecurityIntegrationTest extends PaymentTestBase {
    private static final String BALANCE_PATH = "/api/v1/accounts/{account}/balance";
    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String BEARER = "Bearer ";
    private static final String BROWSER_SESSION = "SESSION=storefront-browser-session";
    private static final String ANONYMOUS_CHALLENGE = "Bearer";
    private static final String REJECTED_CHALLENGE = "Bearer error=\"invalid_token\"";
    private static final String SCOPE_CHALLENGE = "Bearer error=\"insufficient_scope\"";
    private static final String UNRELATED_SCOPE = "openid";
    private static final Duration PAST_CLOCK_SKEW = Duration.ofMinutes(5);

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCredentials")
    void rejectedCredentialsCannotReadOrDebitAndNeverReachFinancialState(String label, String authorization, String challenge) {
        expectUnauthorized(balance(authorization), challenge);
        expectUnauthorized(debit(authorization), challenge);
        assertNoFinancialState();
    }

    @Test
    void eachScopeAloneIsSufficientForItsOwnOperation() {
        UUID account = UUID.randomUUID();
        debit(BEARER + PaymentTokens.token(WRITE), account).expectStatus().isCreated();
        balance(BEARER + PaymentTokens.token(READ), account).expectStatus().isOk()
                .expectBody().jsonPath("$.balanceMinor").isEqualTo(900);
    }

    @ParameterizedTest(name = "debit={0}, scope={1}")
    @MethodSource("tokensWithoutTheRequiredScope")
    void validTokenWithoutTheRequiredScopeIsForbiddenAndWritesNothing(boolean debit, String scope) {
        String authorization = BEARER + PaymentTokens.token(scope);
        expectForbidden(debit ? debit(authorization) : balance(authorization));
        assertNoFinancialState();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unlistedRoutes")
    void unlistedRoutesAreDeniedEvenWithEveryPaymentsScope(HttpMethod method, String path) {
        expectForbidden(web.method(method).uri(path, UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(accessToken())).exchange());
    }

    @Test
    void browserSessionCookieIsNeitherAcceptedNorIssued() {
        web.get().uri(BALANCE_PATH, UUID.randomUUID()).header(HttpHeaders.COOKIE, BROWSER_SESSION).exchange()
                .expectStatus().isUnauthorized().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
        web.get().uri(BALANCE_PATH, UUID.randomUUID()).header(HttpHeaders.COOKIE, BROWSER_SESSION)
                .headers(headers -> headers.setBearerAuth(accessToken())).exchange()
                .expectStatus().isOk().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void keyRotationIsAcceptedOnlyAfterTheProviderPublishesTheNewKey() {
        String authorization = BEARER + PaymentTokens.signWithRotatedKey(valid());
        expectUnauthorized(balance(authorization), REJECTED_CHALLENGE);
        PaymentTokens.publishRotatedKey();
        balance(authorization).expectStatus().isOk();
    }

    /** Без таймаута JWK-клиента запрос ждал бы Keycloak бесконечно и занимал admission-слот; WebTestClient обрывает за 5 с. */
    @Test
    void unresponsiveKeyEndpointFailsClosedWithinTheBoundedTimeout() {
        String authorization = BEARER + PaymentTokens.signWithUnknownKey(valid());
        PaymentTokens.whileKeyEndpointStalls(() -> balance(authorization).expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(503));
    }

    @Test
    void duplicateAuthorizationHeadersAreRejectedBeforeAnyFinancialEffect() {
        web.post().uri(PAYMENTS_PATH).header(HttpHeaders.AUTHORIZATION, BEARER + accessToken(), "Bearer invalid")
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new DebitRequest(UUID.randomUUID(), UUID.randomUUID(), 100L, DebitRequest.CurrencyEnum.RUB))
                .exchange().expectStatus().isUnauthorized();
        assertNoFinancialState();
    }

    static Stream<Arguments> rejectedCredentials() {
        Instant now = Instant.now();
        return Stream.of(
                Arguments.of("no Authorization header", "", ANONYMOUS_CHALLENGE),
                Arguments.of("Basic credentials", "Basic dXNlcjpwYXNzd29yZA==", ANONYMOUS_CHALLENGE),
                Arguments.of("Bearer without a token", "Bearer", REJECTED_CHALLENGE),
                Arguments.of("malformed token", BEARER + "not-a-jwt", REJECTED_CHALLENGE),
                Arguments.of("unsigned token", BEARER + PaymentTokens.unsigned(valid()), REJECTED_CHALLENGE),
                Arguments.of("HS256 token", BEARER + PaymentTokens.signWithSharedSecret(valid()), REJECTED_CHALLENGE),
                Arguments.of("forged signature", BEARER + PaymentTokens.signWithForgedKey(valid()), REJECTED_CHALLENGE),
                Arguments.of("expired token", signed(valid().expirationTime(Date.from(now.minus(PAST_CLOCK_SKEW)))), REJECTED_CHALLENGE),
                Arguments.of("token not yet valid", signed(valid().notBeforeTime(Date.from(now.plus(PAST_CLOCK_SKEW)))), REJECTED_CHALLENGE),
                Arguments.of("wrong issuer", signed(valid().issuer(ISSUER + "-other")), REJECTED_CHALLENGE),
                Arguments.of("wrong audience", signed(valid().audience("not-" + PaymentTokens.AUDIENCE)), REJECTED_CHALLENGE),
                Arguments.of("other OAuth client", signed(valid().claim("azp", CLIENT + "-other")), REJECTED_CHALLENGE),
                Arguments.of("non-string client claim", signed(valid().claim("azp", List.of(CLIENT))), REJECTED_CHALLENGE),
                Arguments.of("missing expiry", signed(valid().expirationTime(null)), REJECTED_CHALLENGE),
                Arguments.of("missing subject", signed(valid().subject(null)), REJECTED_CHALLENGE));
    }

    static Stream<Arguments> tokensWithoutTheRequiredScope() {
        return Stream.of(Arguments.of(false, WRITE), Arguments.of(false, UNRELATED_SCOPE),
                Arguments.of(true, READ), Arguments.of(true, UNRELATED_SCOPE));
    }

    static Stream<Arguments> unlistedRoutes() {
        return Stream.of(Arguments.of(HttpMethod.GET, "/actuator/info"), Arguments.of(HttpMethod.GET, PAYMENTS_PATH),
                Arguments.of(HttpMethod.POST, BALANCE_PATH));
    }

    private static JWTClaimsSet.Builder valid() {
        return PaymentTokens.claims(READ, WRITE);
    }

    private static String signed(JWTClaimsSet.Builder claims) {
        return BEARER + PaymentTokens.sign(claims);
    }

    private WebTestClient.ResponseSpec debit(String authorization) {
        return debit(authorization, UUID.randomUUID());
    }

    private WebTestClient.ResponseSpec debit(String authorization, UUID account) {
        var request = new DebitRequest(account, UUID.randomUUID(), 100L, DebitRequest.CurrencyEnum.RUB);
        return web.post().uri(PAYMENTS_PATH).headers(headers -> authorize(headers, authorization))
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request).exchange();
    }

    private WebTestClient.ResponseSpec balance(String authorization) {
        return balance(authorization, UUID.randomUUID());
    }

    private WebTestClient.ResponseSpec balance(String authorization, UUID account) {
        return web.get().uri(BALANCE_PATH, account).headers(headers -> authorize(headers, authorization)).exchange();
    }

    private static void authorize(HttpHeaders headers, String authorization) {
        if (!authorization.isEmpty()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private static void expectUnauthorized(WebTestClient.ResponseSpec response, String challenge) {
        response.expectStatus().isUnauthorized().expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().jsonPath("$.status").isEqualTo(401);
    }

    private static void expectForbidden(WebTestClient.ResponseSpec response) {
        response.expectStatus().isForbidden().expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, SCOPE_CHALLENGE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(403);
    }

    private void assertNoFinancialState() {
        assertEquals(0, count("payment_wallets"));
        assertEquals(0, count("payment_decisions"));
        assertEquals(0, count("payment_journal"));
    }
}
