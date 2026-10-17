package dev.principalwater.store.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.principalwater.store.ReactiveTestBase;
import dev.principalwater.store.payment.generated.api.PaymentsApi;
import dev.principalwater.store.payment.generated.model.PaymentReceipt;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PaymentsGatewayTest extends ReactiveTestBase {
    private final Scheduler network = Schedulers.newSingle("payment-network-test");
    private final Scheduler codecs = Schedulers.newBoundedElastic(1, 1, "payment-codec-test");
    private final PaymentProperties properties = new PaymentProperties(
            URI.create("http://localhost"), Duration.ofSeconds(1), Duration.ofSeconds(5));

    @AfterEach
    void releaseScheduler() {
        network.dispose();
        codecs.dispose();
    }

    @Test
    void declinedReceiptIsDecodedOutsideTheEventLoop() throws Exception {
        var api = mock(PaymentsApi.class);
        var json = spy(new ObjectMapper());
        var intent = new CheckoutIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 199, Instant.now());
        var receipt = new PaymentReceipt().paymentId(UUID.randomUUID()).accountId(intent.payerId())
                .referenceId(intent.id()).amountMinor(intent.amountMinor()).balanceMinor(0L)
                .currency(PaymentReceipt.CurrencyEnum.RUB).status(PaymentReceipt.StatusEnum.DECLINED);
        byte[] response = json.writeValueAsBytes(receipt);
        when(api.debit(eq(intent.id()), any())).thenReturn(Mono.error(WebClientResponseException.create(
                402, "Payment Required", HttpHeaders.EMPTY, response, null)));
        doAnswer(call -> {
            assertFalse(Schedulers.isInNonBlockingThread(), "JSON decoding must leave the network event loop");
            return call.callRealMethod();
        }).when(json).readValue(any(byte[].class), eq(PaymentReceipt.class));
        var gateway = new PaymentsGateway(api, properties, json, codecs);

        assertEquals(receipt, await(gateway.debit(intent).subscribeOn(network)));
    }

    @Test
    void codecQueueSharesTheDebitDeadline() throws InterruptedException {
        var occupied = new CountDownLatch(1);
        var release = Sinks.<Void>empty();
        codecs.schedule(() -> {
            occupied.countDown();
            release.asMono().block(TIMEOUT);
        });
        try {
            assertTrue(occupied.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            var api = mock(PaymentsApi.class);
            var intent = new CheckoutIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 199, Instant.now());
            when(api.debit(eq(intent.id()), any())).thenReturn(Mono.error(WebClientResponseException.create(
                    402, "Payment Required", HttpHeaders.EMPTY, new byte[0], null)));
            var gateway = new PaymentsGateway(api, properties, new ObjectMapper(), codecs);

            StepVerifier.withVirtualTime(() -> gateway.debit(intent)).thenAwait(properties.debitTimeout())
                    .expectError(TimeoutException.class).verify(Duration.ofSeconds(1));
        } finally {
            release.tryEmitEmpty();
        }
    }
}
