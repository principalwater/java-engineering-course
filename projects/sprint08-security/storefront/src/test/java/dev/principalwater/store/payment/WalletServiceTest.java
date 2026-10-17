package dev.principalwater.store.payment;

import dev.principalwater.store.ReactiveTestBase;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class WalletServiceTest extends ReactiveTestBase {
    private static final String PRIVATE_DETAIL = "private-payment-response";

    static Stream<Throwable> balanceFailures() {
        return Stream.of(new TimeoutException(PRIVATE_DETAIL), new IllegalStateException(PRIVATE_DETAIL),
                WebClientResponseException.create(503, PRIVATE_DETAIL, HttpHeaders.EMPTY, new byte[0], null));
    }

    @ParameterizedTest
    @MethodSource("balanceFailures")
    void unavailableBalanceIsLoggedWithoutPrivateFailureDetails(Throwable failure, CapturedOutput output) {
        var payments = mock(PaymentsGateway.class);
        var payer = UUID.randomUUID();
        when(payments.balance(payer)).thenReturn(Mono.error(failure));

        assertEquals(WalletBalanceView.unavailable(), await(new WalletService(payments).balance(payer)));
        assertTrue(output.getOut().contains("WARN"));
        assertTrue(output.getOut().contains(failure.getClass().getSimpleName()));
        assertFalse(output.getAll().contains(PRIVATE_DETAIL));
        assertFalse(output.getAll().contains(payer.toString()));
    }
}
