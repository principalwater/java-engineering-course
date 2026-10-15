package dev.principalwater.store.payment;

import dev.principalwater.store.PaymentsStub;
import dev.principalwater.store.StoreIntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentOAuthIntegrationTest extends StoreIntegrationTestBase {
    @Autowired private PaymentsGateway payments;
    @Autowired private ReactiveOAuth2AuthorizedClientService clients;

    @BeforeEach
    void clearAuthorizedClient() {
        clients.removeAuthorizedClient("payments", "storefront").block(TIMEOUT);
    }

    @Test
    void oneServiceTokenIsReusedAcrossAccountsAndRenewedAfterExpiry() {
        assertEquals(1_000_000_000L, await(payments.balance(UUID.randomUUID())));
        assertEquals(1_000_000_000L, await(payments.balance(UUID.randomUUID())));
        assertEquals(1, PaymentsStub.TOKEN_REQUESTS.get());
        OAuth2AuthorizedClient previous = clients.loadAuthorizedClient("payments", "storefront").block(TIMEOUT);
        assertNotNull(previous);
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-fixture-token",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(120));
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(previous.getClientRegistration(), "storefront", expired),
                UsernamePasswordAuthenticationToken.unauthenticated("storefront", null)).block(TIMEOUT);
        assertEquals(1_000_000_000L, await(payments.balance(UUID.randomUUID())));
        assertEquals(2, PaymentsStub.TOKEN_REQUESTS.get());
    }

    @Test
    void rejectedTokenIsEvictedWithoutAnAutomaticFinancialRetry() {
        await(payments.balance(UUID.randomUUID()));
        PaymentsStub.REJECT_TOKEN.set(true);
        StepVerifier.create(payments.balance(UUID.randomUUID()))
                .expectError(WebClientResponseException.Unauthorized.class).verify(TIMEOUT);
        assertEquals(1, PaymentsStub.TOKEN_REQUESTS.get());
        await(payments.balance(UUID.randomUUID()));
        assertEquals(2, PaymentsStub.TOKEN_REQUESTS.get());
    }

    @Test
    void unavailableAuthorizationServerFailsClosedBeforeCallingPayments() {
        PaymentsStub.TOKEN_UNAVAILABLE.set(true);
        StepVerifier.create(payments.balance(UUID.randomUUID())).expectError().verify(TIMEOUT);
        assertEquals(1, PaymentsStub.TOKEN_REQUESTS.get());
        assertEquals(0, PaymentsStub.API_REQUESTS.get());
        assertEquals(0, PaymentsStub.DEBITS.get());
    }
}
